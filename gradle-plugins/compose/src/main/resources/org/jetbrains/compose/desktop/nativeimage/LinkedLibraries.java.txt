/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.nativeimage;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Which JNI libraries the build linked into the image, read once while the image is built.
 *
 * Initialised at build time, so the list is part of the image: the property that names the
 * libraries is a build argument and does not exist when the executable runs. Kept apart from
 * the feature because the substitutions that consult it are compiled into the image, and a
 * class implementing a builder interface cannot be.
 */
final class LinkedLibraries {

    static final String PROPERTY = "compose.nativeimage.staticLibraries";

    record Library(String name, List<String> prefixes) {
        static Library parse(String entry) {
            int colon = entry.indexOf(':');
            if (colon < 0) {
                return new Library(entry, List.of());
            }
            return new Library(entry.substring(0, colon), List.of(entry.substring(colon + 1).split("\\|")));
        }
    }

    static final List<Library> ALL = Arrays.stream(System.getProperty(PROPERTY, "").split(","))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .map(Library::parse)
        .toList();

    private LinkedLibraries() {
    }

    static boolean contains(String name) {
        for (Library library : ALL) {
            if (library.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The library name a path to a JNI library file stands for: {@code libawt_lwawt.dylib},
     * {@code awt_lwawt.dll} and {@code libawt_lwawt.so} are all {@code awt_lwawt}.
     */
    static String nameOf(String path) {
        String file = new File(path).getName();
        if (file.startsWith("lib")) {
            file = file.substring(3);
        }
        int dot = file.indexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }
}
