# Gradle multi-module example

A plain Gradle reactor: two subprojects below the root, each with its own `build.gradle`, both
aggregating into one set of generated files at the root.

Until this example existed every multi-module fixture in the repository was Maven, and all four
Gradle examples were single-module, so no CI job ever ran VibeTags across a real Gradle reactor.
The two most recent multi-module defects both came from Gradle repositories.

## Layout

```
gradle-multimodule/
├── settings.gradle     include 'core', include 'app'
├── build.gradle        shared config; the root compiles no sources of its own
├── CLAUDE.md           the opt-in, and where both modules' guardrails land
├── core/build.gradle   its presence is what makes core a module root
└── app/build.gradle
```

## The one thing that matters

Each subproject compiles in its own Gradle worker directory, so the reactor root has to be named:

```groovy
options.compilerArgs << "-Avibetags.root=${rootProject.projectDir.absolutePath}"
```

`rootProject.projectDir`, not `projectDir`. With `projectDir` each module resolves its own root and
writes its own generated files instead of aggregating, which is the whole point of a reactor.

## Expected output

`CLAUDE.md` carries one `VIBETAGS-MODULE` region per subproject, `core` with a locked file and
`app` with a contextual entry, and one sidecar per module (`.vibetags-mod-core`,
`.vibetags-mod-app`).

## Build

```bash
./gradlew clean build --no-daemon
```

The library must be installed locally first (`vibetags-annotations`, `vibetags`, `vibetags-bom`);
see the [root README](../../README.md).
