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
├── .claude/rules/      root granular opt-in; the marker file inside is what enables it
├── core/build.gradle   its presence is what makes core a module root
├── core/.claude/rules/ core's own granular opt-in
└── app/build.gradle    app opts into nothing of its own, on purpose
```

## The one thing that matters

Each subproject compiles in its own Gradle worker directory, so the reactor root has to be named:

```groovy
options.compilerArgs << "-Avibetags.root=${rootProject.projectDir.absolutePath}"
```

`rootProject.projectDir`, not `projectDir`. With `projectDir` each module resolves its own root and
writes its own generated files instead of aggregating, which is the whole point of a reactor.

## Expected output

`CLAUDE.md` carries one `VIBETAGS-MODULE` region per subproject, and there is one sidecar per
module (`.vibetags-mod-core`, `.vibetags-mod-app`).

## Granular rules on a Gradle reactor

The root is opted into both `CLAUDE.md` and `.claude/rules/`, so each module region collapses to a
**scoped-rules index**: one pointer line per element, with the per-element detail in
`.claude/rules/`. The safety buckets stay inline, which is why `core`'s `@AILocked` entry is still
written out in full in `CLAUDE.md` while `app`'s `@AIContext` is only a pointer. An agent has to be
able to see what is locked without opening anything.

`core/` additionally opts into a granular directory of its own, so it gets
`core/.claude/rules/com-example-gmm-core-IrNode.md` scoped to its own annotations. `app/` opts into
nothing, and gets nothing: file presence is the only opt-in, and the asymmetry here is what pins
that. CI asserts `app/.claude` does not exist.

Every other fixture covering this is Maven. Before it was added here, no Gradle build in the
repository exercised the granular path, so the scoped-rules index and the per-module granular write
were verified only on one of the two build tools VibeTags supports.

## Check mode

```bash
./gradlew build -PvibetagsCheck
```

Fails the build when the committed files no longer match the annotations, instead of rewriting
them. The `outputs.upToDateWhen { false }` line in `build.gradle` is what makes that true on a warm
build: check mode runs inside the annotation processor, so it runs only if javac runs, and Gradle
skips an `UP-TO-DATE` `compileJava`. Removing that line and drifting the committed `CLAUDE.md`
produces `compileJava UP-TO-DATE` and exit 0, a gate that green-lights the thing it exists to
catch. CI runs both directions: in sync must pass without the compile being skipped, and a drifted
`CLAUDE.md` must fail while leaving the file untouched.

## Build

```bash
./gradlew clean build --no-daemon
```

The library must be installed locally first (`vibetags-annotations`, `vibetags`, `vibetags-bom`);
see the [root README](../../README.md).
