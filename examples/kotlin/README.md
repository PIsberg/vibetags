# VibeTags Kotlin Example (kapt)

A minimal Kotlin consumer showing VibeTags running under [kapt](https://kotlinlang.org/docs/kapt.html).
The `@AI*` annotations are plain Java annotations with `RetentionPolicy.SOURCE`, so they apply to
Kotlin classes and functions unchanged, and nothing reaches the compiled class file.

## Build

The VibeTags artifacts must be installed locally first (see the repository README), then:

```bash
./gradlew clean build
```

`kapt` runs the annotation processor during `compileKotlin`; the opted-in files in this
directory (`CLAUDE.md`, `.cursorrules`) are regenerated on every build.

## The three lines that matter

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("kapt") version "2.4.10"
}

dependencies {
    compileOnly("se.deversity.vibetags:vibetags-annotations")
    kapt("se.deversity.vibetags:vibetags-processor")
}

kapt {
    arguments {
        // kapt's working directory is not the project directory — pass the root explicitly.
        arg("vibetags.root", projectDir.absolutePath)
    }
}
```

## Kotlin-specific limitations

kapt runs annotation processors over generated **Java stubs**, not the Kotlin sources.
Two consequences, both cosmetic for typical use:

- **Method-body-scoped annotations are invisible.** Stubs carry no method bodies, so an
  `@AI*` annotation on a local declaration inside a function body is never seen by the
  processor. Class-level and function-level annotations — the normal usage — work fully.
- **Source positions refer to the stubs.** The `.vibetags-locks` report's line ranges
  would describe the generated stub, not the `.kt` file, so this example does not opt in
  to the locks report.

KSP is not supported: VibeTags is a JSR 269 processor, and KSP does not run JSR 269
processors. kapt is the supported route for Kotlin, and it is in maintenance mode but
fully functional on Kotlin 2.x.
