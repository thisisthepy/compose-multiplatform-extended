/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.kotlin.gradle.nativedesktop.msvc

import java.io.File

/**
 * A Visual Studio installation that can link a Windows executable: the MSVC toolset, the
 * Windows SDK, and clang-cl when it was found.
 */
data class MsvcToolchain(
    val installPath: String,
    val toolsetVersion: String,
    val toolsetDir: String,
    val windowsSdkVersion: String,
    val windowsSdkDir: String,
    val clangCl: String?,
) {
    val cl: String get() = "$toolsetDir/bin/Hostx64/x64/cl.exe"
    val link: String get() = "$toolsetDir/bin/Hostx64/x64/link.exe"
    val lib: String get() = "$toolsetDir/bin/Hostx64/x64/lib.exe"
    val rc: String get() = "$windowsSdkDir/bin/$windowsSdkVersion/x64/rc.exe"

    /** The INCLUDE directories `cl.exe` and `rc.exe` search, so no developer prompt is needed. */
    val includeDirs: List<String>
        get() = listOf(
            "$toolsetDir/include",
            "$windowsSdkDir/Include/$windowsSdkVersion/ucrt",
            "$windowsSdkDir/Include/$windowsSdkVersion/um",
            "$windowsSdkDir/Include/$windowsSdkVersion/shared",
        )

    /** The LIB directories `link.exe` searches. */
    val libDirs: List<String>
        get() = listOf(
            "$toolsetDir/lib/x64",
            "$windowsSdkDir/Lib/$windowsSdkVersion/ucrt/x64",
            "$windowsSdkDir/Lib/$windowsSdkVersion/um/x64",
        )
}

/** The machine as far as finding MSVC needs it. A test supplies a fake one. */
interface MsvcHost {
    /** Every Visual Studio installation `vswhere` lists with the C++ tools, or empty. */
    fun vswhereInstallPaths(): List<String>
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun listDirectory(path: String): List<String>

    /** `%ProgramFiles(x86)%`, where the Windows SDK lives. */
    val programFilesX86: String
}

/** MSVC versions this build has been linked and run with. */
object MsvcVerifiedVersions {
    /** The oldest toolset the CI Windows runner image links and runs with. Empty until CI recorded it. */
    const val LOWEST: String = ""

    /** The newest toolset verified on a developer machine (Visual Studio 2026). */
    const val HIGHEST: String = "14.51"

    fun describe(): String =
        if (LOWEST.isEmpty()) "up to $HIGHEST" else "$LOWEST to $HIGHEST"
}

class MsvcNotFoundException(message: String) : RuntimeException(message)

/** What to look for. */
data class MsvcRequirements(
    /** The oldest toolset accepted. Null accepts any, which is the default until a floor is verified. */
    val minimumToolset: String? = MsvcVerifiedVersions.LOWEST.ifEmpty { null },
    /** clang-cl is needed only by builds that compile C++ with it, such as the Compose path. */
    val requireClangCl: Boolean = false,
)

object MsvcDetector {
    /**
     * Finds the newest installed toolset that meets [requirements], or throws
     * [MsvcNotFoundException] saying what is missing and how to install it.
     */
    fun locate(host: MsvcHost, requirements: MsvcRequirements = MsvcRequirements()): MsvcToolchain {
        val installs = host.vswhereInstallPaths()
        if (installs.isEmpty()) {
            throw MsvcNotFoundException(installHint("Visual Studio or the Build Tools with the C++ tools were not found"))
        }
        val candidates = installs.flatMap { install ->
            val root = "$install/VC/Tools/MSVC"
            host.listDirectory(root)
                .filter { host.isDirectory("$root/$it") && host.isFile("$root/$it/bin/Hostx64/x64/link.exe") }
                .map { install to it }
        }
        if (candidates.isEmpty()) {
            throw MsvcNotFoundException(installHint("no MSVC toolset with a 64-bit linker was found under ${installs.joinToString()}"))
        }
        val minimum = requirements.minimumToolset
        val (install, toolset) = candidates
            .filter { minimum == null || compareVersions(it.second, minimum) >= 0 }
            .maxWithOrNull { a, b -> compareVersions(a.second, b.second) }
            ?: throw MsvcNotFoundException(
                installHint(
                    "the newest MSVC toolset found is ${candidates.maxOf { it.second }}, " +
                        "which is older than the minimum $minimum"
                )
            )
        val clang = listOf("$install/VC/Tools/Llvm/x64/bin/clang-cl.exe", "$install/VC/Tools/Llvm/bin/clang-cl.exe")
            .firstOrNull { host.isFile(it) }
        if (clang == null && requirements.requireClangCl) {
            throw MsvcNotFoundException(installHint("clang-cl.exe is missing from $install/VC/Tools/Llvm"))
        }
        val sdkRoot = "${host.programFilesX86}/Windows Kits/10"
        val sdk = host.listDirectory("$sdkRoot/Include")
            .filter { host.isFile("$sdkRoot/Include/$it/um/windows.h") }
            .maxWithOrNull { a, b -> compareVersions(a, b) }
            ?: throw MsvcNotFoundException(installHint("no Windows 10 or 11 SDK was found under $sdkRoot"))
        return MsvcToolchain(install, toolset, "$install/VC/Tools/MSVC/$toolset", sdk, sdkRoot, clang)
    }

    /** The message a missing piece ends the build with. */
    fun installHint(problem: String): String = buildString {
        appendLine("A Windows executable needs the MSVC C++ build tools, and $problem.")
        appendLine("Kotlin/Native compiles for Windows with MinGW, and this plugin links the result with Microsoft's linker.")
        appendLine("Install Visual Studio or the \"Build Tools for Visual Studio\" with the workload")
        appendLine("\"Desktop development with C++\", which includes the MSVC x64 build tools and a Windows SDK,")
        appendLine("then build again. Add \"C++ Clang tools for Windows\" if your build uses clang-cl.")
        append("Verified MSVC toolset versions: ${MsvcVerifiedVersions.describe()}.")
    }

    /** Compares dotted numeric versions: `14.51.1` is newer than `14.5`. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/** The real machine: `vswhere` from the Visual Studio Installer directory. */
object RealMsvcHost : MsvcHost {
    override val programFilesX86: String =
        (System.getenv("ProgramFiles(x86)") ?: "C:/Program Files (x86)").replace('\\', '/')

    override fun vswhereInstallPaths(): List<String> {
        val vswhere = File(programFilesX86, "Microsoft Visual Studio/Installer/vswhere.exe")
        if (!vswhere.isFile) return emptyList()
        return try {
            val process = ProcessBuilder(
                vswhere.path, "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property", "installationPath",
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            parseVswhereInstallPaths(out)
        } catch (e: java.io.IOException) {
            emptyList()
        }
    }

    override fun isFile(path: String) = File(path).isFile
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun listDirectory(path: String) = File(path).list()?.toList().orEmpty()
}

/** One installation path per line, as `vswhere -property installationPath` prints them. */
fun parseVswhereInstallPaths(output: String): List<String> =
    output.lineSequence()
        .map { it.trim().replace('\\', '/') }
        .filter { it.isNotEmpty() && (it[0].isLetter() && it.getOrNull(1) == ':' || it.startsWith("/")) }
        .distinct()
        .toList()
