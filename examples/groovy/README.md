# VibeTags Groovy Example (joint-compilation stubs)

A minimal Groovy consumer showing VibeTags running under Gradle's joint compilation.
groovyc generates Java stubs for the Groovy sources, and one switch — off by default —
runs JSR 269 annotation processors over them, the Groovy analogue of kapt:

```groovy
tasks.withType(GroovyCompile).configureEach {
    groovyOptions.javaAnnotationProcessing = true
    options.annotationProcessorPath = configurations.annotationProcessor
    options.compilerArgs << "-Avibetags.root=${projectDir.absolutePath}"
}
```

## Build

The VibeTags artifacts must be installed locally first (see the repository README), then:

```bash
./gradlew clean build
```

The opted-in files (`CLAUDE.md`, `.cursorrules`) are regenerated on every build; CI greps
them for the annotated Groovy elements, so "Groovy works" is a gate, not a claim.

## Limitations

Two are shared with kapt, both stub-inherited: stubs carry no method bodies, so
annotations on declarations inside a method body are invisible; and source positions
describe the stub, so this example does not opt in to the `.vibetags-locks` report.
Class-level and method-level annotations — the normal usage — work fully.

The third is Groovy's own, and this example gates it rather than claims it: **groovyc's
stubs carry no fields at all**, so a field-level annotation on a Groovy field generates
nothing — no output, no warning from the build. `InventoryService.contactEmail` carries a
deliberate `@AIPrivacy` for exactly this reason, and CI asserts the field appears in **no**
generated file. A guardrail that silently is not there is worse than none, so in real Groovy
code put it on the accessor or the class instead. `vibetags doctor` reads the `.groovy`
sources directly and names every field guardrail the stubs dropped; the measured details are
in [docs/JVM-LANGUAGES.md](../../docs/JVM-LANGUAGES.md).
