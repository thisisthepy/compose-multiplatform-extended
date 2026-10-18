pluginManagement {
    repositories {
        // org.thisisthepy.kotlin.native.desktop, published by the workflow from gradle-plugins.
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        // The core fork's Compose for macosArm64 and its window modules, published by the
        // workflow from compose-multiplatform-core-extended.
        mavenLocal()
        mavenCentral()
        google()
    }
}
rootProject.name = "hello-awtfree-kn"
