/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

import java.io.File

enum class WindowsSubsystem(val linkerName: String) { Console("CONSOLE"), Windows("WINDOWS") }

/** One process the pipeline starts. */
data class MsvcCommand(
    val description: String,
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
) {
    val commandLine: List<String> get() = listOf(executable) + arguments
}

class MsvcLinkException(message: String) : RuntimeException(message)

/** Runs a command and returns its exit code and combined output. A test supplies a fake. */
fun interface MsvcCommandRunner {
    fun run(command: MsvcCommand): Pair<Int, String>
}

object ProcessMsvcCommandRunner : MsvcCommandRunner {
    override fun run(command: MsvcCommand): Pair<Int, String> {
        val builder = ProcessBuilder(command.commandLine).redirectErrorStream(true)
        builder.environment().putAll(command.environment)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
}

/** Everything the link needs. */
data class MsvcLinkSpec(
    val name: String,
    val toolchain: MsvcToolchain,
    /** The Kotlin/Native static library for mingwX64, as Kotlin/Native wrote it. */
    val staticLibrary: File,
    /** The MinGW GCC runtime the Kotlin/Native object was compiled against: libstdc++.a, libgcc.a, libgcc_eh.a, libwinpthread.a. */
    val gccRuntimeDir: File,
    val workDir: File,
    val outputFile: File,
    val subsystem: WindowsSubsystem = WindowsSubsystem.Console,
    /** Prebuilt MSVC libraries to link, copied with their runtime directives blanked. */
    val extraLibraries: List<File> = emptyList(),
    /** Link the C++ standard library (libcpmt.lib) from this machine's MSVC, guard blanked. For C++ code built with MSVC, such as Skia. */
    val linkCppStandardLibrary: Boolean = false,
    val manifest: File? = null,
    val icon: File? = null,
    /** Extra system import libraries beyond the ones Kotlin/Native's runtime needs. */
    val systemLibraries: List<String> = emptyList(),
    /** The C function the generated entry calls, which the Kotlin entry file exports. */
    val kotlinEntrySymbol: String = "kotlin_msvc_entry",
    /** Rewrite the MinGW conventions. Off only as a diagnostic. */
    val rewriteMingwObjects: Boolean = true,
)

/**
 * The Windows link step: a Kotlin/Native mingwX64 static library becomes an MSVC executable.
 *
 * It rewrites the MinGW conventions in copies of the library and of the GCC runtime
 * ([CoffMingwFixer]), blanks the runtime directives in prebuilt MSVC libraries
 * ([RuntimeDirectives]), writes a C entry point that calls the Kotlin one, compiles it and a
 * small bridge to MSVC's runtime, and links with `link.exe`. [commands] is the pure plan,
 * [prepare] the file work it needs, and [run] does both and starts the processes.
 *
 * This is the one place the pipeline lives. The standalone `kotlinMsvc` tasks call it, and so
 * can any other plugin code that needs a Windows executable from a Kotlin/Native library.
 */
class MsvcLinkPipeline(
    private val spec: MsvcLinkSpec,
    private val runner: MsvcCommandRunner = ProcessMsvcCommandRunner,
    private val findCppStandardLibrary: (MsvcToolchain) -> File? = { File("${it.toolsetDir}/lib/x64/libcpmt.lib").takeIf(File::isFile) },
) {
    private val work get() = spec.workDir
    private val fixedLibrary get() = File(work, spec.staticLibrary.name)
    private val gccLibraries = listOf("libstdc++.a", "libgcc.a", "libgcc_eh.a", "libwinpthread.a")
    private val entrySource get() = File(work, "entry.c")
    private val bridgeSource get() = File(work, "mingw_bridge.c")
    private val entryObject get() = File(work, "entry.obj")
    private val bridgeObject get() = File(work, "mingw_bridge.obj")
    private val resourceFile get() = File(work, "app.res")
    private val resourceScript get() = File(work, "app.rc")
    private val cppLibraryCopy get() = File(work, "kotlin-msvc-libcpmt.lib")

    private val env: Map<String, String>
        get() {
            val t = spec.toolchain
            val bin = "${t.toolsetDir}/bin/Hostx64/x64"
            val sdkBin = "${t.windowsSdkDir}/bin/${t.windowsSdkVersion}/x64"
            val path = (System.getenv("PATH") ?: System.getenv("Path") ?: "")
            return mapOf(
                "INCLUDE" to t.includeDirs.joinToString(";"),
                "LIB" to t.libDirs.joinToString(";"),
                "PATH" to listOf(bin, sdkBin, path).joinToString(File.pathSeparator),
            )
        }

    fun entrySourceText(): String = when (spec.subsystem) {
        WindowsSubsystem.Console -> """
            |#include <stdlib.h>
            |int ${spec.kotlinEntrySymbol}(int argc, char **argv);
            |int main(int argc, char **argv) { return ${spec.kotlinEntrySymbol}(argc, argv); }
            |""".trimMargin()
        WindowsSubsystem.Windows -> """
            |#include <stdlib.h>
            |int ${spec.kotlinEntrySymbol}(int argc, char **argv);
            |int __stdcall WinMain(void *instance, void *previous, char *command, int show) {
            |    (void)instance; (void)previous; (void)command; (void)show;
            |    return ${spec.kotlinEntrySymbol}(__argc, __argv);
            |}
            |""".trimMargin()
    }

    private fun path(file: File) = file.absolutePath.replace('\\', '/')

    /** The processes, in order. Pure: it reads nothing. */
    fun commands(): List<MsvcCommand> {
        val t = spec.toolchain
        val commands = ArrayList<MsvcCommand>()
        // Compiled for the static runtime's headers and naming no runtime library (/Zl), so the
        // object is answered by whichever C runtime the executable links.
        commands += MsvcCommand(
            "compile the MinGW bridge", t.cl,
            listOf("/nologo", "/O2", "/MT", "/Zl", "/c", "-D_ALLOW_RUNTIME_LIBRARY_MISMATCH",
                "/Fo${path(bridgeObject)}", path(bridgeSource)),
            env,
        )
        commands += MsvcCommand(
            "compile the entry point", t.cl,
            listOf("/nologo", "/O2", "/MD", "/c", "/Fo${path(entryObject)}", path(entrySource)),
            env,
        )
        if (spec.icon != null) {
            commands += MsvcCommand(
                "compile the icon resource", t.rc,
                listOf("/nologo", "/fo", path(resourceFile), path(resourceScript)),
                env,
            )
        }
        val link = ArrayList<String>()
        link += listOf("/NOLOGO", "/OUT:${path(spec.outputFile)}", "/SUBSYSTEM:${spec.subsystem.linkerName}")
        link += listOf(path(entryObject), path(bridgeObject))
        if (spec.icon != null) link += path(resourceFile)
        link += path(fixedLibrary)
        link += gccLibraries.map { path(File(work, "gcc/$it")) }
        link += spec.extraLibraries.map { path(File(work, "libs/${it.name}")) }
        if (spec.linkCppStandardLibrary) link += path(cppLibraryCopy)
        link += listOf(
            "kernel32.lib", "user32.lib", "advapi32.lib", "shell32.lib", "ole32.lib", "bcrypt.lib",
            "ws2_32.lib", "dbghelp.lib", "oldnames.lib", "legacy_stdio_definitions.lib",
        )
        link += spec.systemLibraries
        // The UCRT is part of Windows, vcruntime comes from its static library so the executable
        // needs no Visual C++ runtime DLL, and the stock static C runtime is left out because the
        // UCRT replaces it.
        link += listOf("/NODEFAULTLIB:vcruntime.lib", "libvcruntime.lib", "/NODEFAULTLIB:libcmt.lib")
        if (spec.linkCppStandardLibrary) link += listOf("/NODEFAULTLIB:msvcprt.lib", "/NODEFAULTLIB:libcpmt.lib")
        // winpthread calls longjmp through an import pointer, which a static vcruntime only
        // defines once something has brought it in.
        link += "/INCLUDE:longjmp"
        if (spec.manifest != null) link += listOf("/MANIFEST:EMBED", "/MANIFESTINPUT:${path(spec.manifest)}")
        commands += MsvcCommand("link ${spec.outputFile.name}", t.link, link, env)
        return commands
    }

    /** Copies, rewrites and writes every file [commands] names. */
    fun prepare() {
        work.mkdirs()
        File(work, "gcc").mkdirs()
        File(work, "libs").mkdirs()
        spec.staticLibrary.copyTo(fixedLibrary, overwrite = true)
        if (!fixedLibrary.isFile) throw MsvcLinkException("no static library at ${spec.staticLibrary}")
        rewriteMingw(fixedLibrary)
        for (name in gccLibraries) {
            val source = File(spec.gccRuntimeDir, name)
            if (!source.isFile) throw MsvcLinkException(
                "$name is missing from ${spec.gccRuntimeDir}. Kotlin/Native downloads its MinGW toolchain " +
                    "the first time it compiles for mingwX64; run the mingwX64 build once, then link again."
            )
            val copy = File(work, "gcc/$name")
            source.copyTo(copy, overwrite = true)
            rewriteMingw(copy)
        }
        for (library in spec.extraLibraries) blankRuntime(library, File(work, "libs/${library.name}"))
        if (spec.linkCppStandardLibrary) {
            val cpp = findCppStandardLibrary(spec.toolchain)
                ?: throw MsvcLinkException("libcpmt.lib was not found under ${spec.toolchain.toolsetDir}/lib/x64")
            blankRuntime(cpp, cppLibraryCopy)
        }
        entrySource.writeText(entrySourceText())
        val bridge = MsvcLinkPipeline::class.java.getResourceAsStream("mingw_bridge.c")
            ?: throw MsvcLinkException("the plugin is missing its mingw_bridge.c resource")
        bridgeSource.writeBytes(bridge.use { it.readBytes() })
        spec.icon?.let {
            resourceScript.writeText("1 ICON \"${path(it)}\"\n")
        }
        spec.outputFile.absoluteFile.parentFile.mkdirs()
    }

    private fun rewriteMingw(file: File) {
        if (!spec.rewriteMingwObjects) return
        val bytes = file.readBytes()
        CoffMingwFixer.fixArchive(bytes)
        file.writeBytes(bytes)
    }

    private fun blankRuntime(source: File, destination: File) {
        val bytes = source.readBytes()
        val found = RuntimeDirectives.blank(bytes)
        if (found.unreconcilable.isNotEmpty()) {
            throw MsvcLinkException(RuntimeDirectives.debugRuntimeMessage(source.path, found.unreconcilable))
        }
        destination.writeBytes(bytes)
    }

    fun run() {
        prepare()
        for (command in commands()) {
            val (code, output) = runner.run(command)
            if (code != 0) {
                throw MsvcLinkException(
                    "Could not ${command.description} (exit code $code).\n${command.commandLine.joinToString(" ")}\n$output"
                )
            }
        }
    }
}

/** Where Kotlin/Native keeps its MinGW toolchain, which holds the GCC runtime the object needs. */
object KonanMingwRuntime {
    fun find(konanDataDir: File): File? {
        val dependencies = File(konanDataDir, "dependencies")
        val roots = dependencies.listFiles { f -> f.isDirectory && f.name.startsWith("msys2-mingw-w64-x86_64-") }.orEmpty()
        for (root in roots.sortedByDescending { it.name }) {
            val gcc = File(root, "lib/gcc/x86_64-w64-mingw32").listFiles { f -> f.isDirectory }.orEmpty()
                .maxWithOrNull { a, b -> MsvcDetector.compareVersions(a.name, b.name) } ?: continue
            if (File(root, "x86_64-w64-mingw32/lib/libwinpthread.a").isFile) return DirectoryAssembler.assemble(root, gcc)
        }
        return null
    }

    /** One directory holding the four libraries, so the pipeline needs a single path. */
    private object DirectoryAssembler {
        fun assemble(root: File, gcc: File): File {
            val dir = File(root, ".kotlin-msvc-gcc-runtime")
            dir.mkdirs()
            for (name in listOf("libstdc++.a", "libgcc.a", "libgcc_eh.a")) File(gcc, name).copyTo(File(dir, name), overwrite = true)
            File(root, "x86_64-w64-mingw32/lib/libwinpthread.a").copyTo(File(dir, "libwinpthread.a"), overwrite = true)
            return dir
        }
    }
}

/** The Kotlin the plugin adds to the mingwX64 compilation so the C entry point has something to call. */
object MsvcEntryCode {
    fun kotlinSource(entryPoint: String, passArguments: Boolean, symbol: String = "kotlin_msvc_entry"): String {
        val call = if (passArguments) "$entryPoint(Array(argc) { argv!![it]!!.toKString() })" else "$entryPoint()"
        return """
            |@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
            |
            |package kotlinmsvc.generated
            |
            |import kotlinx.cinterop.ByteVar
            |import kotlinx.cinterop.CPointer
            |import kotlinx.cinterop.CPointerVar
            |import kotlinx.cinterop.get
            |import kotlinx.cinterop.toKString
            |
            |@CName("$symbol")
            |fun $symbol(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
            |    $call
            |    return 0
            |}
            |""".trimMargin()
    }
}

/** Whether the program's `main` takes the command line, read from its source text. */
object MainSignature {
    /**
     * [entryPoint] is `pkg.name` or `name`. A function of that name whose parameter list holds
     * `Array<String>` takes arguments. Anything else, including a source that cannot be found,
     * does not, which is also what Kotlin's own `main()` is.
     */
    fun takesArguments(entryPoint: String, sources: List<String>): Boolean {
        val function = entryPoint.substringAfterLast('.')
        val pkg = entryPoint.substringBeforeLast('.', "")
        val declaration = Regex("""\bfun\s+${Regex.escape(function)}\s*\(([^)]*)\)""")
        for (text in sources) {
            val filePackage = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1) ?: ""
            if (filePackage != pkg) continue
            val match = declaration.find(text) ?: continue
            return match.groupValues[1].replace(" ", "").contains("Array<String>")
        }
        return false
    }
}
