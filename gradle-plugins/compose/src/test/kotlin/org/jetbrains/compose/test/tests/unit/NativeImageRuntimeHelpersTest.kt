/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.tasks.AbstractNativeImageTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * packageNativeImage stops before the link when the MSVC runtime is older than the toolset
 * Skia was built with, rather than letting the link fail on a bare `__std_*` name. The sets
 * below were read with dumpbin from the Windows Skia of skiko's m144 distribution and from
 * two toolsets: 14.43.34808, which cannot link it, and 14.51.36231, which can.
 */
class NativeImageRuntimeHelpersTest {
    /** What Skia's archives call, as dumpbin reports them undefined. */
    private val skiaCalls = setOf(
        "__std_find_first_of_trivial_pos_1",
        "__std_find_trivial_1",
        "__std_max_element_1",
        "__std_type_info_compare",
        "__std_type_info_name",
    )

    /** libcpmt of 14.43 has every one of those but the find_first_of helper. */
    private val cpmt1443 = setOf("__std_find_trivial_1", "__std_max_element_1")

    /** libcpmt of 14.51 gained it. */
    private val cpmt1451 = cpmt1443 + "__std_find_first_of_trivial_pos_1"

    /** The type_info helpers live in libvcruntime, in both toolsets. */
    private val vcruntime = setOf("__std_type_info_compare", "__std_type_info_name")

    @Test
    fun theOldToolsetIsReportedWithTheHelperItLacks() {
        assertEquals(
            listOf("__std_find_first_of_trivial_pos_1"),
            AbstractNativeImageTask.missingRuntimeHelpers(skiaCalls, cpmt1443 + vcruntime),
        )
    }

    @Test
    fun theToolsetThatLinksReportsNothing() {
        assertEquals(
            emptyList<String>(),
            AbstractNativeImageTask.missingRuntimeHelpers(skiaCalls, cpmt1451 + vcruntime),
        )
    }

    /**
     * Reading only the C++ runtime would fail a toolset that links: the type_info helpers are
     * not in libcpmt in any toolset, so they would be reported missing on 14.51 as well.
     */
    @Test
    fun theCxxRuntimeAloneIsNotEnoughToJudgeBy() {
        assertEquals(
            listOf("__std_type_info_compare", "__std_type_info_name"),
            AbstractNativeImageTask.missingRuntimeHelpers(skiaCalls, cpmt1451),
        )
    }

    @Test
    fun theMessageNamesTheToolsetAndWhatIsMissing() {
        val runtimes = listOf(
            File("C:/MSVC/14.43.34808/lib/x64/libcpmt.lib"),
            File("C:/MSVC/14.43.34808/lib/x64/libvcruntime.lib"),
        )
        val message = AbstractNativeImageTask.runtimeHelpersMessage(
            listOf("__std_find_first_of_trivial_pos_1"), runtimes,
        )
        assertTrue("14.43.34808" in message, "the message should name the toolset: $message")
        assertTrue("__std_find_first_of_trivial_pos_1" in message, "it should name the helper: $message")
        assertTrue("Update the Visual Studio C++ toolset" in message, "it should say what to do: $message")
    }

    @Test
    fun onlyRuntimeHelperNamesAreTaken() {
        val line = "  005 00000000 UNDEF  notype ()    External     | __std_find_trivial_1"
        assertEquals(
            listOf("__std_find_trivial_1"),
            AbstractNativeImageTask.RUNTIME_HELPER.findAll(line).map { it.value }.toList(),
        )
        val unrelated = "  007 00000000 UNDEF  notype ()    External     | memcpy"
        assertEquals(0, AbstractNativeImageTask.RUNTIME_HELPER.findAll(unrelated).count())
    }
}
