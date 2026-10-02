/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.nativeimage;

import java.lang.reflect.Method;
import org.graalvm.nativeimage.hosted.Feature;

/**
 * Tells a native image that the desktop libraries a Compose application loads are already
 * linked into it, so it never goes looking for them as files.
 *
 * A Compose desktop application reaches native code through JNI libraries that a JVM opens
 * as files at run time: AWT's toolkit, the JAWT bridge Skiko draws through, and Skia itself.
 * A native image that should be one executable links their static archives instead, and
 * this is what makes the runtime agree: each name is registered as a library that is already
 * inside the image, with the packages whose native methods belong to it, so
 * {@code System.loadLibrary} runs its static {@code JNI_OnLoad_<name>} rather than opening a
 * file.
 *
 * The libraries are named by the {@code compose.nativeimage.staticLibraries} property, a
 * comma separated list of {@code name:prefix|prefix} entries, written by the build that also
 * links the archives. Naming a library here without linking its archive fails at link time,
 * which is the outcome to want.
 *
 * NONE OF THIS IS A PUBLIC GRAALVM API. It is under {@code com.oracle.svm.core}, documented
 * nowhere, and may change in any release, so it fails loudly when it is not found rather
 * than falling back to loading files, which would be an image that works only beside the
 * libraries it was supposed to contain.
 */
public final class StaticDesktopLibrariesFeature implements Feature {

    @Override
    public String getDescription() {
        return "Links the desktop JNI libraries a Compose application loads into the image";
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        String value = System.getProperty(LinkedLibraries.PROPERTY);
        return value != null && !value.isEmpty();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        Object support = singleton("com.oracle.svm.core.jdk.NativeLibrarySupport");
        Object platform = singleton("com.oracle.svm.core.jdk.PlatformNativeLibrarySupport");
        try {
            Method preregister = support.getClass().getMethod("preregisterUninitializedBuiltinLibrary", String.class);
            Method addPrefix = platform.getClass().getMethod("addBuiltinPkgNativePrefix", String.class);
            for (LinkedLibraries.Library library : LinkedLibraries.ALL) {
                preregister.invoke(support, library.name());
                for (String prefix : library.prefixes()) {
                    addPrefix.invoke(platform, prefix);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw internalApiChanged(e);
        }
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        try {
            Object nativeLibraries = access.getClass().getMethod("getNativeLibraries").invoke(access);
            Method addStatic = nativeLibraries.getClass().getMethod("addStaticJniLibrary", String.class, String[].class);
            for (LinkedLibraries.Library library : LinkedLibraries.ALL) {
                addStatic.invoke(nativeLibraries, library.name(), new String[0]);
            }
        } catch (ReflectiveOperationException e) {
            throw internalApiChanged(e);
        }
    }

    private static Object singleton(String className) {
        try {
            Object instance = Class.forName(className).getMethod("singleton").invoke(null);
            if (instance == null) {
                throw new IllegalStateException(className + " has no singleton");
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw internalApiChanged(e);
        }
    }

    private static IllegalStateException internalApiChanged(Throwable cause) {
        return new IllegalStateException(
            "This GraalVM no longer has the internal interface that registers a library as linked "
                + "into the image. The single executable build depends on it; use a GraalVM it was "
                + "checked against, or build the distributable folder instead.",
            cause);
    }
}
