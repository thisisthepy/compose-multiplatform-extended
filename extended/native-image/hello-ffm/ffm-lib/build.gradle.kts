// A library that calls C through the Foreign Function and Memory API and carries the
// reachability metadata for those calls in its own jar, under META-INF/native-image, the way a
// published library would. The application that uses it registers nothing for them.
plugins {
    `java-library`
}

// The FFM API is final from Java 22. The build runs on an older JDK, so the library is compiled
// by the GraalVM the image is built with (GRAALVM_HOME, see gradle.properties).
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(22) }
