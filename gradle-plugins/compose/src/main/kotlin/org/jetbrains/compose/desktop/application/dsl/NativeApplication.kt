/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import javax.inject.Inject

abstract class NativeApplication @Inject constructor(
    @Suppress("unused")
    val name: String,
    objects: ObjectFactory
) {
    @get:Inject
    internal abstract val objects: ObjectFactory

    internal val _targets = arrayListOf<KotlinNativeTarget>()

    /** Linking settings shared by every target. See [NativeBinarySettings]. */
    val binarySettings: NativeBinarySettings = objects.newInstance(NativeBinarySettings::class.java)
    fun binarySettings(fn: Action<NativeBinarySettings>) {
        fn.execute(binarySettings)
    }

    fun targets(vararg targets: KotlinTarget) {
        val nonNativeTargets = arrayListOf<KotlinTarget>()
        val unsupportedTargets = arrayListOf<KotlinNativeTarget>()
        for (target in targets) {
            if (target is KotlinNativeTarget) {
                if (target.konanTarget.family in supportedFamilies) {
                    _targets.add(target)
                } else {
                    unsupportedTargets.add(target)
                }
            } else {
                nonNativeTargets.add(target)
            }
        }

        check(nonNativeTargets.isEmpty() && unsupportedTargets.isEmpty()) {
            buildString {
                appendLine("compose.nativeApplication.targets supports Kotlin/Native macOS, Linux and mingwX64 targets:")
                nonNativeTargets.forEach { appendLine("* '${it.name}' is not a native target;") }
                unsupportedTargets.forEach { appendLine("* '${it.name}' is not a desktop target;") }
            }
        }
    }

    val distributions: NativeApplicationDistributions = objects.newInstance(NativeApplicationDistributions::class.java)
    fun distributions(fn: Action<NativeApplicationDistributions>) {
        fn.execute(distributions)
    }

    private companion object {
        val supportedFamilies = setOf(Family.OSX, Family.LINUX, Family.MINGW)
    }
}
