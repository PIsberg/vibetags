plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("kapt") version "2.4.10"
}

group = "se.deversity.vibetags.example"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // BOM is the single source of truth for vibetags-* versions. Apply it to both the
    // compile and kapt configurations so neither needs an explicit version.
    implementation(platform("se.deversity.vibetags:vibetags-bom:1.2.1"))
    kapt(platform("se.deversity.vibetags:vibetags-bom:1.2.1"))

    // Annotations on compile, processor on the kapt path only — keeps the processor's
    // slf4j/logback off the consumer's compile classpath. compileOnly is enough:
    // every @AI* annotation is RetentionPolicy.SOURCE and never reaches the class file.
    compileOnly("se.deversity.vibetags:vibetags-annotations")
    kapt("se.deversity.vibetags:vibetags-processor")
}

kapt {
    arguments {
        // kapt compiles from generated Java stubs, so the JVM working directory is not
        // the project directory — the root must be passed explicitly.
        arg("vibetags.root", projectDir.absolutePath)
    }
}
