plugins {
    // Provisions the JDK 21 toolchain automatically when the host JDK is newer —
    // kapt runs javac-based annotation processing, so the example pins the JDK
    // the rest of the repository targets instead of whatever happens to run Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vibetags-example-kotlin"
