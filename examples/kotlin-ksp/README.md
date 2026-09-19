# VibeTags Kotlin Example (KSP)

The Kotlin consumer from [`../kotlin/`](../kotlin/README.md), compiled through
[KSP](https://kotlinlang.org/docs/ksp-overview.html) instead of kapt. It has no sources of its own:
`build.gradle.kts` points the main source set at `../kotlin/src/main/kotlin`, so one input goes
through two front ends.

## Build

The VibeTags artifacts, `vibetags-ksp` included, must be installed locally first (see the
repository README), then:

```bash
./gradlew clean build
```

## What CI checks

The generated `CLAUDE.md` and `.cursorrules` must equal `../kotlin/`'s committed files byte for
byte. A difference is an element path or an ordering that would change for every project that
switches from kapt to KSP. The group and project name match `../kotlin/` on purpose: an `internal`
function's path embeds the Kotlin module name, which Gradle derives from them.

The build must also print this warning:

```
w: [ksp] VibeTags: @AILocked on fun balanceFor in com.example.kotlin.AccountLedger reaches no guardrail file: ...
```

`balanceFor` takes a value class, so its JVM name is mangled and kapt writes no stub for it. Under
kapt that guardrail disappears without a word; under KSP the element is left out too, which keeps the
paths identical, and the build says so.

## The lines that matter

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.12"
}

dependencies {
    compileOnly("se.deversity.vibetags:vibetags-annotations")
    ksp("se.deversity.vibetags:vibetags-ksp")
}

ksp {
    arg("vibetags.root", projectDir.absolutePath)
}
```

Differences from kapt, and why: [USAGE.md](../../USAGE.md#kotlin-ksp-configuration).
