/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

/*
 * The JNI_OnLoad_<name> a native image requires of every library linked into it statically.
 *
 * GraalVM refuses to treat a library as linked in unless it defines one, and skiko does not:
 * a JVM opens it as a file and calls no initialisation. Neither has anything to do here.
 */
#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad_skiko(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_8;
}
