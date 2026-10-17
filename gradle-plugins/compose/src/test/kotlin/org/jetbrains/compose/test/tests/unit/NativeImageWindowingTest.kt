/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.compose.desktop.application.dsl.ApplicationOutput
import org.jetbrains.compose.desktop.application.dsl.ApplicationWindowing
import org.jetbrains.compose.desktop.application.internal.JvmApplicationData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An application built as a native image links no AWT unless it asks for it. */
class NativeImageWindowingTest {
    private fun data() = ProjectBuilder.builder().build().let { JvmApplicationData(it.objects, it.providers) }

    @Test
    fun jvmOutputIsTheDefaultAndKeepsAwt() {
        val data = data()
        assertEquals(ApplicationOutput.Jvm, data.output.get())
        assertEquals(ApplicationWindowing.Awt, data.windowing.get())
    }

    @Test
    fun nativeImageOutputIsAwtFreeByDefault() {
        val data = data()
        data.output.set(ApplicationOutput.NativeImage)
        assertEquals(ApplicationWindowing.AwtFree, data.windowing.get())
    }

    @Test
    fun awtCanBeChosenForNativeImage() {
        val data = data()
        data.output.set(ApplicationOutput.NativeImage)
        data.windowing.set(ApplicationWindowing.Awt)
        assertEquals(ApplicationWindowing.Awt, data.windowing.get())
    }
}
