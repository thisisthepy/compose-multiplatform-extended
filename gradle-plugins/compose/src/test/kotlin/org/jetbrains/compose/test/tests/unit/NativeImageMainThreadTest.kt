/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.test.tests.unit

import org.jetbrains.compose.desktop.application.internal.NativeImageMainThread
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeImageMainThreadTest {
    @Test
    fun libericaNikIsRefusedWithTheFix() {
        val release = """
            IMPLEMENTOR="BellSoft"
            JAVA_RUNTIME_VERSION="25.0.4.1+2-LTS"
            JAVA_VERSION="25.0.4.1"
        """.trimIndent()
        val problem = NativeImageMainThread.problem(release)
        assertNotNull(problem)
        assertTrue(problem!!.contains("graalvm-community"), problem)
    }

    @Test
    fun upstreamGraalVmIsAccepted() {
        val release = """
            IMPLEMENTOR="GraalVM Community"
            JAVA_VERSION="25.0.1"
        """.trimIndent()
        assertNull(NativeImageMainThread.problem(release))
    }

    @Test
    fun theOptionKeepsMainOnTheFirstThread() {
        assertTrue("-H:-RunMainInNewThread" in NativeImageMainThread.options)
    }
}
