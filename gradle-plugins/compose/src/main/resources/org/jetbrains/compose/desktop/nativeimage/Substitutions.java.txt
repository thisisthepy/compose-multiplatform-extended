/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.util.function.BooleanSupplier;

/**
 * The two places a Compose desktop application opens a native library by path, made to find
 * the copy linked into the image instead.
 *
 * Both are load-by-path, which a built-in library registration does not answer: it answers
 * {@code System.loadLibrary(name)} and nothing else.
 */
final class Substitutions {

    private Substitutions() {
    }

    /** True when the build links libraries statically, which is when these apply. */
    static final class Static implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return !LinkedLibraries.ALL.isEmpty();
        }
    }

    /** True when Skia is among them. */
    static final class StaticSkiko implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return LinkedLibraries.contains("skiko");
        }
    }
}

/**
 * AWT's own initialisation loads its platform toolkit by path from the directory libawt sits
 * in, through {@code System.load}. There is no such directory in a single executable. A path
 * that names a library linked into the image is answered by loading that built-in library by
 * name, which runs its static initialisation, and every other path loads as before.
 */
@TargetClass(value = System.class, onlyWith = Substitutions.Static.class)
final class Target_java_lang_System {
    @Substitute
    public static void load(String filename) {
        String name = LinkedLibraries.nameOf(filename);
        if (LinkedLibraries.contains(name)) {
            System.loadLibrary(name);
        } else {
            Runtime.getRuntime().load(filename);
        }
    }
}

/**
 * Skiko does not use {@code System.loadLibrary} on the desktop: it reads
 * {@code skiko.library.path} or unpacks Skia out of its jar, then loads it by absolute path.
 * With Skia linked into the image there is nothing to find or unpack, and the method that
 * does both is replaced by nothing. Skiko's lock around it, its once-only flag and its own
 * initialisation afterwards are left as they are.
 */
@TargetClass(className = "org.jetbrains.skiko.LibraryLoader", onlyWith = Substitutions.StaticSkiko.class)
final class Target_org_jetbrains_skiko_LibraryLoader {
    @Substitute
    private void findAndLoadLibrary(String name, String additionalFile) {
    }
}
