# JVM language support: maturity and limitations

VibeTags is a JSR 269 annotation processor, so one question decides everything on this page:

> Does the language's toolchain ever hand **javac** a declaration carrying the annotation?

Nothing in VibeTags is language-specific. There is no Kotlin code path and no Groovy renderer. A
language is supported to exactly the extent that its compiler produces something javac sees, and
the differences below are differences between those toolchains, not between features anyone chose
to build.

This page states each rating and, more importantly, **how it is checked**. Every claim here was
measured against a third-party repository on a pinned commit, by the `JVM-Language Corpus (Kotlin,
Groovy, Scala)` job that runs on every pull request. Before that job existed, this page's contents
were prose that nobody had run, and one of the claims was wrong for several releases.

## The ratings

| Rating | Means |
|---|---|
| **Supported** | Every level VibeTags addresses is rendered. Verified on code nobody wrote for VibeTags. |
| **Supported, one level lost** | Most levels render; a named level does not, for a reason outside VibeTags' control. |
| **Partial by construction** | Only part of the build ever reaches the processor. |
| **Not possible** | No path even in principle. |

| Language | Rating | What reaches the processor | Verified by |
|---|---|---|---|
| Java | **Supported** | javac runs the processor directly | `corpus/run-corpus.sh`, six libraries, 15,683 members |
| Kotlin | **Supported, one level lost** (package) | kapt generates Java stubs and runs the processor over them | `kotlin-obd-api`, 15 of 15 guardrails rendered |
| Groovy | **Supported, one level lost** (field) | groovyc stubs, but only with `javaAnnotationProcessing` on | `nf-boost`, 13 of 13 expected, 2 field guardrails absent |
| Scala | **Partial by construction** | the Java sources of a mixed module; never `.scala` | `splain`, 17 from the Java half, 0 from the Scala half |
| Clojure | **Not possible** | nothing | n/a |

---

## Java

The reference implementation, and the only one with no caveat. Every level VibeTags addresses
renders: package, type, nested type, field, constructor, method, parameter.

Measured continuously over six libraries at pinned commits, roughly 495 files and 15,683 members,
none of which were written with VibeTags in mind. See [corpus/README.md](../corpus/README.md).

---

## Kotlin

**Rating: supported, one level lost.**

Every `@AI*` annotation is a plain Java annotation with `SOURCE` retention, so it applies to Kotlin
declarations unchanged. kapt generates a Java stub per Kotlin class and runs JSR 269 processors
over the stubs.

### What was measured

15 of 15 guardrails the showcase declares reached the generated files, across **type, nested type,
field, constructor, method and parameter**. `@AIPrivacy` on a Kotlin property renders as
`vibetagscorpus.CorpusShowcase.billingEmail`, in the safety bucket, inline in the Tier-1 aggregate
where it belongs. Claude, Gemini and Codex all produced output, and both granular rule directories
were written.

### What is lost, and why

- **The package level.** Kotlin has no `package-info.kt`, so there is no compilation unit for a
  package annotation to live on. Thirteen annotations accept `ElementType.PACKAGE` and none of them
  can be used from Kotlin. There is no workaround; put the guardrail on the types instead.
- **Method-body-scoped annotations.** Stubs carry no method bodies, so an annotation on a local
  declaration inside a function is never seen. Class-level and function-level annotations, which is
  the normal usage, work fully.
- **Source positions describe the stub.** The `.vibetags-locks` report's line ranges would point
  into the generated stub rather than the `.kt` file, so do not opt a pure-Kotlin module into the
  locks report.

### Use-site targets are load-bearing

A Kotlin property is a getter, a setter and possibly a backing field. An annotation written bare on
a property does not necessarily land where a Java author would expect, and getting it wrong does
not fail the build: it moves the guardrail onto a different element, or drops it. Annotations
declaring only `ElementType.FIELD` should be written with the `@field:` target:

```kotlin
@field:AIPrivacy(reason = "Billing email identifies a natural person; never log it")
private var billingEmail: String? = null
```

### The strategic risk, stated plainly

Kotlin support rests entirely on kapt, and **kapt is in maintenance mode**: JetBrains keeps it
current with new Kotlin and Java releases but has no plans to add features, and recommends KSP for
annotation processing ([kapt](https://kotlinlang.org/docs/kapt.html),
[KSP overview](https://kotlinlang.org/docs/ksp-overview.html),
[migration guide](https://kotlinlang.org/docs/ksp-kapt-migration.html)).

KSP is not a route for VibeTags as it stands. KSP defines its own processor interface
(`SymbolProcessor`) rather than implementing `javax.annotation.processing.Processor`, so a JSR 269
processor is not loadable by it. A Kotlin project that has migrated fully to KSP cannot run
VibeTags at all.

This is the largest single exposure in the matrix. Kotlin support does not degrade gradually if
kapt is ever withdrawn; it goes from "one level lost" to nothing, and the only in-principle fix is
a separate KSP processor reading the same annotations. Nothing about that is imminent, and no
deprecation has been announced. It is written down here because the alternative is finding out
from a release note.

---

## Groovy

**Rating: supported, one level lost.**

groovyc emits a Java stub per Groovy class and javac runs the processors over the stubs, the same
shape as kapt. The switch is off by default in Gradle, so a Groovy project that adds VibeTags and
changes nothing else generates **nothing at all, silently**:

```groovy
tasks.withType(GroovyCompile).configureEach {
    groovyOptions.javaAnnotationProcessing = true
    options.annotationProcessorPath = configurations.annotationProcessor
    options.compilerArgs << "-Avibetags.root=${projectDir.absolutePath}"
}
```

### Field-level annotations are dropped

This is the limitation worth reading twice, because it is silent and it affects a safety
annotation.

groovyc's stub carries the class, its constructors, its methods and their parameters, with every
annotation intact, and **no fields whatsoever**. So `@AIPrivacy`, `@AISecureLogging`,
`@AIPerformance` and every other `ElementType.FIELD` annotation on a Groovy field reaches no
processor and generates nothing. There is no warning, because nothing in the compilation ever sees
the annotation.

`@AIPrivacy` is one of the six safety annotations that stay inline in the Tier-1 aggregate,
precisely so they reach an agent that never opens the file. In Groovy, marking a PII field does
nothing, and the build stays green.

The obvious explanation is wrong, which is worth recording. A Groovy field with no access modifier
becomes a property, so the natural theory is that the annotation moved onto a generated accessor.
It did not. Keeping the stubs (`groovyOptions.keepStubs`) and reading one settles it:

```java
@AIContext(...) @AIPublicAPI() public class CorpusShowcase
@AILocked(reason="...CONSTRUCTOR-LOCKED...") public CorpusShowcase
@AILocked(reason="...METHOD-LOCKED...") public long invoiceNumber(long id) { return (long)0;}
@AIContract(...) public String render(@AIInputSanitized(...) String customerNote, ...)
```

The three annotated fields appear nowhere in it. Declaring them `private` does not help: that only
stops Groovy making them properties, and the stub omits them either way.

**What to do instead:** put the guardrail on the accessor or on the enclosing type, or keep the
rule in the hand-authored region outside the `VIBETAGS-START`/`END` markers. `vibetags doctor`
finds the annotations this applies to: it reads the `.groovy` sources directly — which the build
cannot — and reports each field-level guardrail as a finding with its file, line and annotation
([#494](https://github.com/PIsberg/vibetags/issues/494)).

This is pinned rather than merely written down. The Groovy showcase in the corpus annotates fields
on purpose, the harness expects those two guardrails to be missing, and it **fails if they ever
start appearing** - at which point this section gets rewritten instead of quietly aging.

### Most Groovy projects cannot switch stub processing on at all

The levels above describe what happens once it works. Whether it works is a separate question, and
for real Groovy code the answer is often no. Eight Groovy repositories were surveyed before one
could be used as a corpus member:

| Repository | Outcome once `javaAnnotationProcessing` is on |
|---|---|
| `jenkinsci/JenkinsPipelineUnit` | stub rejected: `'_' is a keyword, and may not be used as an identifier` |
| `int128/gradle-ssh-plugin` | stub rejected: `illegal combination of modifiers: public and private` |
| `longwa/build-test-data` | stub rejected: `method does not override or implement a method from a supertype` |
| `jk1/Gradle-License-Report` | toolchain pinned to Java 17: `class file version 65.0` |
| `allegro/axion-release-plugin` | toolchain pinned to Java 17: same |
| `jwagenleitner/groovy-wslite` | wrapper too old: `Could not determine java version from '21.0.9'` |
| `gpc/grails-postgresql-extensions` | compiles |
| `bentsherman/nf-boost` | compiles |

Three of the eight compile perfectly well on their own and break the moment stubs are generated,
each on a different stub defect. That is a property of groovyc's stub generator, not of VibeTags,
and a user hitting it sees a compile error in a file they did not write, under
`build/tmp/compileGroovy/groovy-java-stubs`.

**Try it on a branch before committing to it.** If the stubs compile, Groovy support is real; if
they do not, no amount of VibeTags configuration will help.

---

## Scala

**Rating: partial by construction.**

scalac has no JSR 269 support of any kind. Java annotations are legal in Scala and compile without
complaint, so an author can annotate a Scala class, see a green build, and get no guardrails at
all. Only the Java sources of a mixed module reach the processor, compiled by javac as normal.

### Both directions are measured

Asserting the negative matters as much as the positive here, and the corpus does both in one build:

- the **Java** half must generate everything: 17 of 17 guardrails, including the package level.
- the **Scala** half is annotated at every level it can be, and every one of those markers must be
  absent from every generated file.
- the Scala showcase must have produced class files. Without that third check, "scalac generated
  nothing" and "scalac was never asked" are the same green run.

If the negative ever fails, either scalac has gained annotation processing or VibeTags has found
another way in, and this section needs rewriting rather than the test relaxing.

### What to do instead

Put guardrails on thin annotated Java types next to the Scala they protect; javac compiles
`src/main/java` normally in a mixed module. For rules that have no Java surface, the hand-authored
region outside the `VIBETAGS-START`/`END` markers is the right home, and it survives regeneration.

---

## Clojure

**Rating: not possible.** There is no javac in the pipeline, and Clojure's metadata annotations emit
only `CLASS` or `RUNTIME` retention into bytecode. `SOURCE` retention cannot be expressed at all, so
the annotations cannot even be written. Same fallback as Scala: annotated Java types, or
hand-authored rules outside the markers.

---

## One trap that applies to every language

**The compiling JDK must be 21 or newer, and a Gradle toolchain overrides the JDK you launched
with.** This:

```groovy
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
```

runs javac from a JDK 17, which cannot load the processor:

```
class file version 65.0, this version of the Java Runtime only recognizes class file versions up to 61.0
```

The message names neither VibeTags nor the toolchain, so it reads like a corrupt jar. Two of the
Groovy projects above fail exactly this way. Gradle plugins and libraries pin older toolchains for
consumer compatibility far more often than application code does, so it is worth checking first.

---

## How these ratings are kept honest

Each row of the first table is a test, not a belief:

| Job | What it runs |
|---|---|
| `Third-Party Corpus` | `corpus/run-corpus.sh` - six Java libraries, twice each, with and without VibeTags |
| `JVM-Language Corpus (Kotlin, Groovy, Scala)` | `corpus/run-corpus-jvm.sh` - three repositories built twice by their own Gradle wrapper |

Both compare a control build against a treatment build, so "it compiled" is never the assertion.
Sixteen assertions cover the JVM-language leg, including the tier split, both granular directories
and the parameter level. Two of them exist specifically to keep this page true:

- **the expected set is derived from the showcase**, so a level that stops rendering fails rather
  than being absorbed by a margin someone left in;
- **the exclusions are checked in the other direction too**, so a limitation that quietly gets
  fixed fails the build and forces this page to be corrected. A documentation error in the generous
  direction is the one nobody ever notices, because the run is green and more guardrails arrived
  than were asked for.

Full detail, including the assertion table:
[corpus/README.md](../corpus/README.md#the-other-three-jvm-languages).

## What is not known

Stated so that nobody mistakes silence for evidence:

- **Kotlin is verified on one Kotlin version.** The corpus member is on Kotlin 2.3.10 and
  `examples/kotlin` on 2.4.10. Nothing here sweeps a range of Kotlin releases.
- **One kapt diagnostic is unattributed.** kapt reports `vibetags.root` as an unrecognised
  processor option even though `AIGuardrailProcessor` declares it in `@SupportedOptions` and
  demonstrably receives it. It appears on 2.3.10 and not on 2.4.10. Tracked as an open question,
  not asserted to be harmless.
- **Groovy is verified on one project.** `nf-boost` compiles its stubs; the survey above shows how
  much that varies. There is no claim about Groovy versions or about Grails.
- **Scala is verified on one Gradle-built project.** Gradle-built Scala is rare; sbt is far more
  common and is not exercised anywhere. Nothing here says what happens under sbt, only that scalac
  itself offers nothing to a processor, which is true regardless of build tool.
- **Android is not covered at all.** No corpus member uses the Android Gradle plugin.
