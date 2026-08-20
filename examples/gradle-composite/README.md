# Gradle example: composite build (`includeBuild`)

`app/` and `lib/` are two **separate Gradle builds**. `app/settings.gradle` pulls the other in
with `includeBuild '../lib'`, and Gradle substitutes the dependency instead of resolving it from
a repository.

## Why this layout needs a decision

There is no single Gradle root containing both builds: each has its own `settings.gradle` and
resolves its own root. Left alone, each build writes its own set of generated files in its own
directory, so a repository that is one project to a reader is two to VibeTags.

## The fix

Point both builds at the directory that contains them:

```groovy
options.compilerArgs << "-Avibetags.root=${rootProject.projectDir.parentFile.absolutePath}"
options.compilerArgs << "-Avibetags.module=app"   // and "lib" in the other build
```

Both participating builds then aggregate into one `CLAUDE.md` at `examples/gradle-composite/`,
one region each. The module name is given explicitly because each build's root project name is
its own, and `app`/`lib` read better in a shared file than
`vibetags-example-gradle-composite-app`.

Measured output: `.vibetags-mod-app` and `.vibetags-mod-lib`, with `com.example.gcomp.app.Gateway`
and `com.example.gcomp.lib.Codec` in their own regions.

## Build

```bash
cd app && ./gradlew clean build --no-daemon
```

Building `app` builds `lib` too, because the composite substitution makes it a dependency.
