/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.tasks.AbstractNativeImageTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * packageNativeImage defines skiko's other-platform JNI entry points by the names the image
 * refers to them by. A name mangled differently from the JVM's is a stop that defines nothing,
 * and the image dies at load on the symbol it was meant to cover.
 */
class NativeImageJniNameTest {
    private fun jni(className: String, method: String) =
        "Java_" + AbstractNativeImageTask.jniMangle(className) + "_" + AbstractNativeImageTask.jniMangle(method)

    @Test
    fun underscoresInNamesAreEscaped() {
        // The symbol skiko's macOS build left undefined and the first proof stopped on.
        assertEquals(
            "Java_org_jetbrains_skiko_AWTLinuxDrawingSurfaceKt_getDisplay",
            jni("org/jetbrains/skiko/AWTLinuxDrawingSurfaceKt", "getDisplay"),
        )
        assertEquals(
            "Java_org_jetbrains_skia_PaintKt_Paint_1nMake",
            jni("org/jetbrains/skia/PaintKt", "Paint_nMake"),
        )
    }

    @Test
    fun nonAsciiAndSpecialCharactersAreEscaped() {
        // _0 then four hex digits, as the JNI specification writes a Unicode character.
        assertEquals("a_000e9", AbstractNativeImageTask.jniMangle("aé"))
        assertEquals("a_2_3", AbstractNativeImageTask.jniMangle("a;["))
    }
}
