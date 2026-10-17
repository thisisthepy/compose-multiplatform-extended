/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.thisisthepy.sample.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Three C library functions called through the Foreign Function and Memory API: one downcall
 * with no arguments, one that reads memory the caller allocated, and one that calls back into
 * Java. A native image has a stub for each shape only when it was registered at build time;
 * this library registers them in its jar's META-INF/native-image.
 */
public final class NativeCalls {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static final MethodHandle GETPID = downcall("getpid",
        FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final MethodHandle STRLEN = downcall("strlen",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    private static final MethodHandle QSORT = downcall("qsort",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private NativeCalls() {}

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = LIBC.find(name)
            .orElseThrow(() -> new IllegalStateException("the C library has no " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /** True when this code's module may call restricted methods without a warning. */
    public static boolean nativeAccessEnabled() {
        return NativeCalls.class.getModule().isNativeAccessEnabled();
    }

    public static int pid() throws Throwable {
        return (int) GETPID.invokeExact();
    }

    public static long length(String text) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            return (long) STRLEN.invokeExact(arena.allocateFrom(text));
        }
    }

    /** Sorts with the C library's qsort, which calls {@link #compare} back for every comparison. */
    public static int[] sort(int[] values) throws Throwable {
        MethodHandle compare = MethodHandles.lookup().findStatic(NativeCalls.class, "compare",
            MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment comparator = LINKER.upcallStub(compare,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
            MemorySegment array = arena.allocateFrom(ValueLayout.JAVA_INT, values);
            QSORT.invokeExact(array, (long) values.length, ValueLayout.JAVA_INT.byteSize(), comparator);
            return array.toArray(ValueLayout.JAVA_INT);
        }
    }

    static int compare(MemorySegment a, MemorySegment b) {
        int left = a.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
        int right = b.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
        return Integer.compare(left, right);
    }
}
