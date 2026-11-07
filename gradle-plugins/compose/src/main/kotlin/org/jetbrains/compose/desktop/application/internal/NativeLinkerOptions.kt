package org.jetbrains.compose.desktop.application.internal

import org.jetbrains.kotlin.konan.target.Family

/**
 * The linker options a desktop Kotlin/Native executable needs for the window layer, per
 * platform. Kept free of Gradle types so a unit test can check them.
 */
internal fun nativeDesktopLinkerOpts(
    family: Family,
    skikoDirectory: String?,
    linkWindowSystem: Boolean,
    extra: List<String>
): List<String> {
    val opts = arrayListOf<String>()
    when (family) {
        Family.OSX -> {
            for (framework in listOf("AppKit", "Metal", "MetalKit", "QuartzCore", "CoreGraphics", "CoreText")) {
                opts += listOf("-framework", framework)
            }
        }
        Family.LINUX -> if (linkWindowSystem) {
            opts += listOf("-lX11", "-lXext", "-lXi", "-lXrandr", "-lXcursor", "-lGL", "-lfontconfig", "-lfreetype")
        }
        Family.MINGW -> {
            // The Windows link is configured by the MSVC path; nothing is added here.
        }
        else -> error("$family is not a desktop target")
    }
    if (skikoDirectory != null) opts += "-L$skikoDirectory"
    opts += extra
    return opts
}
