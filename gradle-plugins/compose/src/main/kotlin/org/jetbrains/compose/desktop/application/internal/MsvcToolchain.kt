package org.jetbrains.compose.desktop.application.internal

import java.io.File

/** What a Windows Kotlin/Native build links against: MSVC's toolset, clang-cl and the Windows SDK. */
internal data class MsvcToolchain(
    val installPath: String,
    val toolsetVersion: String,
    val toolsetDir: String,
    val clangCl: String,
    val windowsSdkVersion: String,
    val windowsSdkDir: String
)

/** The machine, as far as locating MSVC needs it. A test supplies fake paths. */
internal interface MsvcHost {
    /** The Visual Studio installation `vswhere` reports, or null when there is none. */
    fun vswhereInstallPath(): String?
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun listDirectory(path: String): List<String>
    /** `%ProgramFiles(x86)%`, where the Windows SDK lives. */
    val programFilesX86: String
}

internal class MsvcNotFoundException(message: String) : RuntimeException(message)

internal const val MIN_MSVC_TOOLSET = "14.51"

internal fun installMsvcHint(missing: String): String = buildString {
    appendLine("Building a Windows (mingwX64) desktop application needs the MSVC Build Tools: $missing.")
    appendLine("Every mingwX64 target is linked as an MSVC executable, so there is no MinGW-only fallback.")
    appendLine("Install Visual Studio Build Tools 2022 or later with these components, then build again:")
    appendLine("  * MSVC v143 or newer C++ x64/x86 build tools, toolset $MIN_MSVC_TOOLSET or later")
    appendLine("  * C++ Clang compiler for Windows (clang-cl)")
    append("  * Windows 10 or 11 SDK")
}

/** Compares dotted numeric versions: `14.51.1` is newer than `14.5`. */
internal fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/**
 * Finds MSVC v[MIN_MSVC_TOOLSET] or later, clang-cl and the Windows SDK, or throws
 * [MsvcNotFoundException] saying what is missing and how to install it.
 */
internal fun locateMsvc(host: MsvcHost): MsvcToolchain {
    val install = host.vswhereInstallPath()
        ?: throw MsvcNotFoundException(installMsvcHint("vswhere found no Visual Studio installation with the C++ tools"))
    val msvcRoot = "$install/VC/Tools/MSVC"
    val toolset = host.listDirectory(msvcRoot)
        .filter { host.isDirectory("$msvcRoot/$it") }
        .maxWithOrNull { a, b -> compareVersions(a, b) }
        ?: throw MsvcNotFoundException(installMsvcHint("no toolset under $msvcRoot"))
    if (compareVersions(toolset, MIN_MSVC_TOOLSET) < 0) {
        throw MsvcNotFoundException(installMsvcHint("the newest toolset is $toolset, which is older than $MIN_MSVC_TOOLSET"))
    }
    val clang = listOf("$install/VC/Tools/Llvm/x64/bin/clang-cl.exe", "$install/VC/Tools/Llvm/bin/clang-cl.exe")
        .firstOrNull { host.isFile(it) }
        ?: throw MsvcNotFoundException(installMsvcHint("clang-cl.exe is not under $install/VC/Tools/Llvm"))
    val sdkRoot = "${host.programFilesX86}/Windows Kits/10"
    val sdk = host.listDirectory("$sdkRoot/Include")
        .filter { host.isFile("$sdkRoot/Include/$it/um/windows.h") }
        .maxWithOrNull { a, b -> compareVersions(a, b) }
        ?: throw MsvcNotFoundException(installMsvcHint("no Windows SDK under $sdkRoot/Include"))
    return MsvcToolchain(install, toolset, "$msvcRoot/$toolset", clang, sdk, sdkRoot)
}

/** The real machine: `vswhere` from the Visual Studio Installer directory. */
internal object RealMsvcHost : MsvcHost {
    override val programFilesX86: String =
        System.getenv("ProgramFiles(x86)") ?: "C:/Program Files (x86)"

    override fun vswhereInstallPath(): String? {
        val vswhere = File(programFilesX86, "Microsoft Visual Studio/Installer/vswhere.exe")
        if (!vswhere.isFile) return null
        return try {
            val process = ProcessBuilder(
                vswhere.path, "-latest", "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property", "installationPath"
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            out.lineSequence().firstOrNull()?.trim()?.replace('\\', '/')?.takeIf { it.isNotEmpty() }
        } catch (e: java.io.IOException) {
            null
        }
    }

    override fun isFile(path: String) = File(path).isFile
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun listDirectory(path: String) = File(path).list()?.toList().orEmpty()
}
