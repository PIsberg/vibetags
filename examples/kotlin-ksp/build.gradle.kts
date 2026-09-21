plugins {
    kotlin("jvm") version "2.4.10"
    // KSP in place of kapt. KSP cannot load a JSR 269 processor, so the dependency below is
    // vibetags-ksp, which runs the same processor behind a KSP front end.
    id("com.google.devtools.ksp") version "2.3.12"
}

// Same group and project name as ../kotlin, on purpose: an internal function's JVM name embeds the
// Kotlin module name, which the Kotlin Gradle plugin builds from these two. With them equal, this
// build must regenerate ../kotlin's committed files byte for byte, and CI checks exactly that.
group = "se.deversity.vibetags.example"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

// The kapt example's own sources, compiled through KSP instead: one input, two front ends.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("../kotlin/src/main/kotlin"))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform("se.deversity.vibetags:vibetags-bom:1.3.6"))
    ksp(platform("se.deversity.vibetags:vibetags-bom:1.3.6"))

    // compileOnly is enough: every @AI* annotation is RetentionPolicy.SOURCE.
    compileOnly("se.deversity.vibetags:vibetags-annotations")
    ksp("se.deversity.vibetags:vibetags-ksp")
}

ksp {
    // KSP runs inside the Gradle daemon, whose working directory is not the project, so the root
    // must be passed explicitly (the adapter warns when it is not).
    arg("vibetags.root", projectDir.absolutePath)
    // Inherited guardrails: KSP gives a processor no view of the classpath's resources, so, as
    // under kapt, manifests are read from a directory of pre-extracted files. The kapt example's.
    arg("vibetags.manifest.dir", file("../kotlin/vibetags-manifests").absolutePath)
    arg("vibetags.manifest.max", "1")
}
