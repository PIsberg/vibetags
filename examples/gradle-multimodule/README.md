# Gradle multi-module example

A Gradle reactor, and the fixture that verifies the Gradle side of VibeTags to the same depth as
the Maven side. Four subprojects below the root, each with its own `build.gradle`, all aggregating
into one set of generated files at the root.

Until this example existed every multi-module fixture in the repository was Maven, so no CI job
ever ran VibeTags across a real Gradle reactor. It then spent several releases asserting one thing
where the Maven reactor asserted eleven, while the three most recent multi-module defects all came
from Gradle repositories: the thinner coverage sat on the tool producing the bugs (issue #443).

## Layout

```
gradle-multimodule/
├── settings.gradle        include core, app, platform, tests
├── build.gradle           shared config; the root compiles no sources of its own
├── .vibetags-manifest     publishes package guardrails, with the origin coordinate
├── .vibetags-transitive   reads guardrails published by dependencies
├── .vibetags-roles        routes core and app into one shared role file
├── CLAUDE.md + 50 more    every service opted in; presence is the opt-in
├── core/                  @AILocked class + package-level guardrails that travel
│   ├── CLAUDE.md          nested aggregate, indexed because of the dir below
│   └── .claude/rules/     the module's own granular output
├── app/                   depends on core, so it inherits core's package guardrails
├── platform/              outside the role glob, so it keeps per-class rule files
│   └── CLAUDE.md          nested aggregate, plain: no granular dir of its own
└── tests/                 no annotations; mirrors its siblings' rules
    ├── .vibetags-mirror
    └── .claude/rules/     mirrored-<module>-<stem>.md
```

## The one thing that matters for the layout

Each subproject compiles in its own Gradle worker directory, so the reactor root has to be named:

```groovy
options.compilerArgs << "-Avibetags.root=${rootProject.projectDir.absolutePath}"
```

`rootProject.projectDir`, not `projectDir`. With `projectDir` each module resolves its own root and
writes its own generated files instead of aggregating, which is the whole point of a reactor.

## What each module is for

Nothing here is decoration. Each module exists because some assertion needs a shape it provides.

| Module | Why it exists |
|---|---|
| `core` | Publishes package-level guardrails (transitive), and opts into *both* a nested aggregate and a nested granular dir, which is the shape that exposed the spurious-warning defect |
| `app` | Imports the core package, so it is the consumer proving a published manifest is actually read |
| `platform` | Sits outside the role glob, so per-class rule files stay covered next to the role-merged ones; opts into a nested aggregate only |
| `tests` | Carries no annotations at all, so mirroring is the only thing that can put rules in it |

Every module carries at least one safety-tier guardrail on purpose. Several renderers emit safety
families only, `.plandex.yaml` among them, so a module whose annotations are all advisory is simply
absent from those files. Without a safety-tier witness per module the YAML check below reports a
merge failure that never happened.

## Transitive guardrails

`core/src/main/java/com/example/gmm/core/package-info.java` declares guardrails on the *package*,
which is what makes them travel. With `.vibetags-manifest` present, the module publishes them to
`core/build/classes/java/main/vibetags/manifests/com.example.gmm.core.json`, which the `jar` task
packages with no further configuration. That path is Gradle's, not Maven's `target/classes/`, and a
manifest written to the wrong directory is unreadable downstream with nothing else to notice.

`app` imports that package and has `.vibetags-transitive` opted in, so the rules render into its
region under "Inherited Guardrails", attributed to the coordinate the marker file declares. Plain
Gradle needs no extra options for this: its incremental-processing wrapper still exposes the
compile classpath.

## Roles, and the two stem namespaces

`.vibetags-roles` routes `core` and `app` into one `reactor-spine.md` per granular directory. Both
modules write the same path, which is the layout of issue #365, where each module's compile
replaced the shared file with only its own classes and the sibling's guardrails vanished. CI
asserts both survive, and that building a single subproject leaves the file byte-identical.

`platform` is outside the glob, so its classes keep per-class files and both paths stay covered.

A module's *own* granular directory resolves against that module's role config, which it does not
have, so `core/.claude/rules/` keeps per-class names while the root role-routes the same elements
into the spine. Those two namespaces meeting in one sidecar field is what produced a spurious
"your guardrails are stated nowhere" warning on builds where every file was written correctly. Fixed
alongside this fixture; CI now asserts a clean build of an in-sync reactor emits no warnings at all.

## Check mode

```bash
./gradlew build -PvibetagsCheck
```

Fails the build when the committed files no longer match the annotations, instead of rewriting
them. The `outputs.upToDateWhen { false }` line in `build.gradle` is what makes that true on a warm
build: check mode runs inside the annotation processor, so it runs only if javac runs, and Gradle
skips an `UP-TO-DATE` `compileJava`. Removing that line and drifting the committed `CLAUDE.md`
produces `compileJava UP-TO-DATE` and exit 0, a gate that green-lights the thing it exists to catch.

## Expected output

51 active services, one `VIBETAGS-MODULE` region per annotated subproject in every aggregate, one
sidecar per module, `reactor-spine.md` in nine granular directories, and mirrored rules under
`tests/.claude/rules/`. `tests` gets no region of its own: mirroring must never put a module into
the reactor root. `.codex/` stays empty because Codex is dropped unless `AGENTS.md` is the sole AI
config file, which it is not here.

## Build

```bash
./gradlew clean build --no-daemon
```

The library must be installed locally first (`vibetags-annotations`, `vibetags`, `vibetags-bom`);
see the [root README](../../README.md). The generated files are committed and CI compares them
against a fresh build, so an annotation edit is not finished until they are regenerated and
committed with it. Clear `.vibetags-cache` first: the fingerprint short-circuit skips the write
when inputs look unchanged, which makes "no diff" ambiguous.
