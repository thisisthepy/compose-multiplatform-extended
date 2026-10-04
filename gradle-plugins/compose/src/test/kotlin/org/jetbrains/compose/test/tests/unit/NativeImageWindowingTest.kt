/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.compose.desktop.application.dsl.NativeImageSettings
import org.jetbrains.compose.desktop.application.dsl.NativeImageWindowing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An application built by packageNativeImage links no AWT unless it asks for it. */
class NativeImageWindowingTest {
    private fun settings() = ProjectBuilder.builder().build().objects.newInstance(NativeImageSettings::class.java)

    @Test
    fun awtFreeIsTheDefault() {
        assertEquals(NativeImageWindowing.AwtFree, settings().windowing.get())
    }

    @Test
    fun awtCanBeChosen() {
        val settings = settings()
        settings.windowing.set(NativeImageWindowing.Awt)
        assertEquals(NativeImageWindowing.Awt, settings.windowing.get())
    }
}
