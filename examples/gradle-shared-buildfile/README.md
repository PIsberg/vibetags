# Gradle example: subprojects configured from the root build file

A common Gradle style: `settings.gradle` declares the subprojects, and all of their
configuration lives in the root `build.gradle`. Neither `core/` nor `app/` has a build file of
its own.

## The trap this example exists to pin

VibeTags finds a module root by walking up from the source file to the nearest `pom.xml`,
`build.gradle` or `build.gradle.kts`. `settings.gradle` is deliberately **not** one of those
markers, because it names the root of a Gradle build rather than a module inside it.

So in this layout the walk from `core/src/main/java/...` passes straight through `core/` and
lands on the root. Every subproject resolves to the same module identity, writes the same
sidecar, and overwrites the one before it. Measured, with the `-Avibetags.module` line below
removed:

```
.vibetags-mod-_root_          <- one sidecar for two modules

<project_guardrails>
  <locked_files>
    <file path="com.example.gsb.core.Ledger"> ...
```

`com.example.gsb.app.Runner` is simply gone. Not stale, not duplicated: absent. Whichever
subproject compiled last is the only one whose guardrails survive, and nothing says so.

## The remedy

Name the module explicitly, which costs one line:

```groovy
options.compilerArgs << "-Avibetags.module=${project.name}"
```

With it, each subproject gets its own identity and both survive:

```
.vibetags-mod-core
.vibetags-mod-app
```

This example ships **with** the remedy applied, and CI asserts both modules reach the
aggregate, so a regression that reintroduces the collapse turns the build red.

If your subprojects each have their own `build.gradle`, you do not need this: see
[gradle-multimodule](../gradle-multimodule/README.md), where the build files are what supply
the identity.

## Build

```bash
./gradlew clean build --no-daemon
```
