/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.tasks.AbstractNativeImageTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The window layer's C sources are found by name inside a core-extended checkout. */
class NativeImageWindowSourcesTest {
    @TempDir
    lateinit var root: File

    @Test
    fun findsASourceInANestedModuleDirectory() {
        val source = root.resolve("graalvm/linux/c/x11_window.c").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(source, AbstractNativeImageTask.findWindowSource(root, "x11_window.c"))
    }

    @Test
    fun reportsAMissingSourceAndAMissingDirectory() {
        assertNull(AbstractNativeImageTask.findWindowSource(root, "appkit_window.m"))
        assertNull(AbstractNativeImageTask.findWindowSource(root.resolve("absent"), "appkit_window.m"))
    }
}
