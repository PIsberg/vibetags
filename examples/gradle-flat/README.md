# Gradle example: flat layout (module beside the root, not below it)

The Gradle root is `app/`, and `lib/` sits beside it rather than under it, via a `projectDir`
override in `settings.gradle` (`includeFlat` produces the same shape).

## Why this layout needs a decision

VibeTags computes each module's path relative to the VibeTags root. A module that is not under
that root has no meaningful relative path, so `computeModulePath` returns `""` and
`computeModuleId` falls back to a hash of the compilation root's absolute path. Three things
follow, all read from the resolver rather than guessed:

1. The region marker becomes an opaque hex id instead of a module name.
2. That id derives from the **absolute** path, so a different checkout location produces a
   different id. Committed generated files would then not reproduce across machines, and check
   mode would report drift on CI.
3. `modulePath` is empty, so the staleness check skips the directory-existence test and the
   sidecar is never pruned when the module goes away.

## The fix

Point the VibeTags root at the directory that actually contains both modules, which here is the
parent of the Gradle root:

```groovy
def vibetagsRoot = rootProject.projectDir.parentFile.absolutePath
options.compilerArgs << "-Avibetags.root=${vibetagsRoot}"
```

Both modules are then under the root, get real relative paths and real names, and aggregate
normally. Measured output: `.vibetags-mod-lib` and `.vibetags-mod-vibetags-example-gradle-flat`,
each with its own region.

`-Avibetags.module` is also passed, so the root project is named rather than inheriting whatever
its directory happens to be called.

## Build

```bash
cd app && ./gradlew clean build --no-daemon
```
