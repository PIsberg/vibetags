plugins {
    // Provisions the JDK 21 toolchain when the host JDK is newer, as in ../kotlin.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Deliberately the kapt example's name: see build.gradle.kts for why the two must match.
rootProject.name = "vibetags-example-kotlin"
