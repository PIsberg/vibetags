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
| Kotlin | **Supported, one level lost** (package) | kapt generates Java stubs and runs the processor over them; functions with a value class in their JVM signature are left out | `kotlin-obd-api`, 15 of 15 guardrails rendered; `examples/kotlin` pins the value-class loss |
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
- **Functions with a value class in their JVM signature.** A function that takes a
  `@JvmInline value class`, or a member function that returns one, gets a mangled JVM name
  (`balanceFor-oKSF6Yo`), and kapt leaves every such function out of the Java stub. An `@AI*`
  annotation on the function, or on any of its parameters, reaches no processor: nothing is generated and nothing is logged. That covers your own
  value classes and the standard library's (`UInt`, `ULong`, `kotlin.time.Duration`). Measured on
  Kotlin 2.4.10: `AccountLedger.balanceFor(AccountId)` in `examples/kotlin`, and a function returning
  `Duration`, were absent from the stub and from every generated file. The #496 spike lost the same
  shape on `UserId`, `UserId?` and `ULong` parameters, a `UserId` return type and a parameter-level
  `@AIInputSanitized`. Three look-alikes are not affected: a `kotlin.Result` parameter is not
  mangled and renders as `settle(java.lang.Object)`, a value class used only as a type argument
  (`List<AccountId>`) leaves the name alone, and so does a top-level function's return type
  (`fun makeId(): AccountId`, #692). Constructors, property accessors and members declared inside a value class are lost the same way;
  the measured table is under
  [what `vibetags doctor` finds](#functions-with-a-value-class-in-their-signature-and-what-vibetags-doctor-finds).
  **What to do instead:** give the function an explicit
  `@JvmName`. kapt then emits it, and the guardrail renders under that name with the value class
  erased to its underlying type, `closeAccount(java.lang.String)` (measured). Or put the guardrail on
  the enclosing type. The `examples/kotlin` CI step fails if `balanceFor` ever appears in a generated
  file, so a kapt release that starts emitting these functions forces this bullet to be rewritten
  (#681).
- **Method-body-scoped annotations.** Stubs carry no method bodies, so an annotation on a local
  declaration inside a function is never seen. Neither is any guardrail inside an object expression
  (`object : Lookup { ... }`, even one that initialises a property), an enum entry's body, or a local
  class, nor one on the local class itself: all were absent from `CLAUDE.md` and from kapt's stubs,
  with and without `-Xjvm-expose-boxed` (#713), and `vibetags doctor` reports them. Class-level and
  function-level annotations on named declarations, which is the normal usage, work fully.
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

### The path of an `internal` function names the Kotlin module

An `internal` function's JVM name carries the Kotlin module name, and VibeTags' element path is the
JVM name, so the module name is part of every path for it:

```
com.example.kotlin.AccountLedger.reconcile$se_deversity_vibetags_example_vibetags_example_kotlin(java.lang.String)
```

The Kotlin Gradle plugin builds that name from the project's `group` and name, with dots and hyphens
turned into `_`. Changing either renames the path: with `group = "com.acme"` and the project renamed
`ledger`, the same function renders as `reconcile$com_acme_ledger(java.lang.String)`. The path
appears in the aggregates, in the function's `.vibetags-locks` entry and inside its class's granular
rule file. The rule file's name is per class and does not change: none of the #496 spike's 36 rule
filenames carried the suffix. Measured on Kotlin 2.4.10; the `examples/kotlin` CI step pins the
suffixed path (#681).

To keep the path stable, give the function an explicit `@JvmName` (`replayBatch(java.lang.String)`,
measured), or pin the module name with `kotlin { compilerOptions { moduleName.set("...") } }` (the
#496 spike set it to `shapes` and got `internalFun$shapes`). Otherwise commit the regenerated files
together with the rename.

### Why VibeTags cannot warn about the lost functions

The processor sees only the stub, so the omitted function is not there to warn about. Its one trace
is the `@kotlin.Metadata` annotation kapt copies onto the stub class, whose string table does list
`balanceFor-oKSF6Yo`. That cannot carry a warning, because it cannot say whether the function had a
guardrail. Metadata records `hasAnnotations` only for annotations kept in the binary, and every
`@AI*` annotation has `SOURCE` retention. Read with `kotlin-metadata-jvm` 2.4.10 from the #496
spike's compiled `Methods` class, 36 of its 38 `@AILocked` functions report `hasAnnotations=false`;
the 2 that report `true` also carry `@JvmName` or `@JvmOverloads`. A metadata-based check could
therefore only say "this class has value-class functions", which is true of every function taking a
`UInt` or a `Duration`, annotated or not, and it would put a protobuf decoder for an undocumented
format into the processor every consumer runs. Not built. The CI step above pins the loss instead,
and `vibetags doctor` reads the sources instead, the route the Groovy field loss took (#494); see
the next section (#688).

### Functions with a value class in their signature, and what `vibetags doctor` finds

A function that takes a `@JvmInline value class` gets a mangled JVM name (`balanceFor-oKSF6Yo`), and
kapt leaves every such declaration out of its Java stubs. An `@AI*` annotation on it, or on one of
its parameters, reaches no processor: nothing is generated and nothing is logged. The processor
cannot warn (previous section). **What to do instead:** give the function an explicit `@JvmName`,
which switches mangling off and makes kapt emit it, or put the guardrail on the enclosing type.

The same mechanism reaches further than functions. #692 built one fixture through kapt with an
`@AILocked` (or, on parameters, `@AIInputSanitized`) on every shape below, 103 guardrails in all,
then built it again with `-Xjvm-expose-boxed`, and read each guardrail back from the generated `CLAUDE.md`:
54 lost by default, 16 with the option. Kotlin 2.4.10, kapt, JDK 21. "Lost" means absent from
`CLAUDE.md`; no lost `@AILocked` reason appears anywhere in `build/tmp/kapt3/stubs` either. #713
added the shapes #692 had not built to the same fixture, 54 more guardrails on Kotlin 2.4.10 and
JDK 21, read back the same way: 34 lost by default, 20 with the option. The rebuild left every #692
result unchanged.

| Guardrail on | Default | `-Xjvm-expose-boxed` | Doctor |
|---|---|---|---|
| Top-level function taking a value class, receiver included (`fun AccountId.describe()`, `AccountId?.x()`) | lost | kept | reports |
| Top-level function returning one (`makeId(): AccountId`, `Duration`, `UInt`, `AccountId?`; private, internal, suspend) | kept | kept | silent |
| Top-level `suspend` function taking one | lost | lost | reports |
| Receiver or parameter `List<AccountId>`, `kotlin.Result` parameter, `vararg raws: String` | kept | kept | silent |
| `vararg ids: AccountId` | does not compile: "Prohibited vararg parameter type" | | |
| Member taking or returning one: class, object, `@JvmStatic`, companion, enum, data class, nested, private, internal, override, member extension | lost | kept | reports |
| Member `suspend` function taking or returning one | lost | lost | reports |
| Interface member (abstract or default), `open` or `abstract` member | lost | lost | reports |
| Property, bare or `@field:` (top-level, member, private, nullable `var`, constructor `val`) | kept | kept | silent |
| Top-level `@get:`, with or without a backing field | kept | kept | silent |
| Top-level `@set:` or `@setparam:` | lost | kept | reports |
| Member `@get:`, `@set:` or `@setparam:` (class, object, companion, constructor `val`) | lost | kept | reports |
| Interface, `open` or `abstract` member `@get:` | lost | lost | reports |
| `@get:JvmName` or `@set:JvmName` on the property | kept | kept | silent |
| Extension property `@get:` with a value-class receiver (`val AccountId.label`, `AccountId?`), top-level or member | lost | kept | reports |
| Extension property `@set:` or `@setparam:` with a value-class receiver or type, top-level or member | lost | kept | reports |
| Top-level extension property `@get:` with a plain receiver returning one (`val String.asId: AccountId`) | kept | kept | silent |
| Member extension property `@get:` with a plain receiver returning one | lost | kept | reports |
| Extension property on `List<AccountId>`, or of plain types | kept | kept | silent |
| Any guardrail in an object expression (initialising a top-level or member property, or returned from a function), an enum entry's body or a local class, and on the local class and its constructors, whatever the signature | lost | lost | reports |
| Constructor taking one, primary or secondary, or a plain parameter of it | lost | kept | reports |
| `@param:` on a constructor `val` of a value-class type | lost | kept | reports |
| `@param:` on a plain-typed constructor `val`; bare `@AIInputSanitized` on a constructor `val` or `var` of a value-class type (renders on the field) | kept | kept | silent |
| A guardrail targeting `PARAMETER` but not `FIELD` | none exists: `AIInputSanitized`, `AISecureLogging` and `AILoadBearing` target both | | |
| Secondary constructor without one, in the same class | kept | kept | silent |
| The value class itself, and `@get:` on its underlying property | kept | kept | silent |
| Function or `@get:` property declared inside a value class, not an override, whatever its signature (`describe-impl`) | lost | kept | reports |
| Plain `override` inside a value class: function, `@get:`, `toString()` (each keeps an instance bridge) | kept | kept | silent |
| `override` inside a value class whose signature takes or returns one, function or `@get:` | lost | kept | reports |
| `suspend` function inside a value class, any signature, including one carrying `@JvmExposeBoxed` | lost | lost | reports |
| Guardrail on a value class's primary constructor, or `@param:` on its property (`constructor-impl`) | lost | kept | reports |
| Bare `@AIInputSanitized` on a value class's property (renders on the field), or `@JvmExposeBoxed` on the value class | kept | kept | silent |
| Secondary constructor declared inside a value class (`constructor-impl`) | lost | lost | reports |
| Companion of a value class: a plain function / a function returning one | kept / lost | kept / kept | follows the member rows |
| `@JvmExposeBoxed` on the function or constructor (top-level, member, value-class member) | kept | kept | silent |
| Function elsewhere taking a value class that carries `@JvmExposeBoxed` | lost | kept | reports |
| Direct member of a class or value class carrying `@JvmExposeBoxed` (function, getter, constructor) | kept | kept | silent |
| ...its `@set:` or `@setparam:`, or a secondary constructor of it, with or without a value class or a parameter guardrail | kept | kept | silent |
| ...its `suspend` member, or a member of a class nested inside it | lost | lost / kept | reports |
| `@JvmExposeBoxed` on an interface member | does not compile: "cannot expose functions which are open or abstract, or member of an interface" | | |
| Function taking a value class declared in another Gradle module | lost | kept | reports when the module is under `--dir`, or its jar is on `--classpath` |
| Function taking a standard-library value class not in the built-in list (`UIntArray`) | lost | kept | reports when `kotlin-stdlib` is on `--classpath` |

Two results overturn what #688 assumed. A return type mangles only a member: a top-level
`fun makeId(): AccountId` keeps its guardrail, and doctor reported it until #692. And
`-Xjvm-expose-boxed` does not make every finding wrong, which is why doctor used to skip the whole
check under it: it keeps final, non-suspend declarations and nothing else.

`vibetags doctor` reads the `.kt` sources and reports each lost declaration as a finding: file and
line, the guardrails it loses, the value class responsible, and a remedy that the table shows to
work (`@JvmName`, `@get:JvmName`, `@field:`, `@JvmExposeBoxed` on a constructor, or the guardrail
on the enclosing type or the value class). It follows the Doctor column: a shape measured as kept produces
nothing, and so does a shape that was not built, with two inferences. An `open` or `abstract` member
of a class carrying `@JvmExposeBoxed` is reported, because the compiler's own error says it cannot
expose those. And in an object expression, an enum entry's body or a local class, `@field:`,
`@setparam:` and parameter guardrails are reported alongside the measured shapes, because the stub
carries none of those declarations at all. The rebuilt fixture also holds five guardrails measured
for nested and deprecated `inline` value classes (#714), all lost by default and kept with the option,
so it loses 94 (55 + 34 + 5) by default and 36 (16 + 20) with the option. With the `model` module's
classes on `--classpath`, doctor reports 91 by default: the losses, less the `UIntArray` parameter
(`kotlin-stdlib` was not passed) and the three that name a nested value class, plus `examples/kotlin`'s
`balanceFor`. Under `-Xjvm-expose-boxed` it reports exactly the 36. No finding names a kept guardrail. It is a heuristic over source text, not a compiler, and it is
built to miss rather than to report something that is not lost:

- **Value classes it knows.** Every `value class` (or `inline class`) declared under the scanned
  directory, plus five from the standard library: `UByte`, `UShort`, `UInt` and `ULong` ("Unsigned
  numbers are implemented as inline classes",
  [Kotlin docs](https://kotlinlang.org/docs/unsigned-integer-types.html)) and
  `kotlin.time.Duration` (declared `@JvmInline value class`,
  [API reference](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-duration/)). A type
  name is resolved through the file's package and imports, so `java.time.Duration`, or an ordinary
  class sharing a value class's simple name, is not a hit. Running doctor from a reactor root scans
  every module under it, so a value class declared in a sibling module is known.
- **Value classes from dependencies (#691).** `doctor --classpath <entries>` also reads every jar
  and class directory given, separated by the platform path separator as for `java -cp`, and
  counts a top-level class whose class file carries `@kotlin.jvm.JvmInline`. kotlinc writes that
  annotation into the class file's `RuntimeVisibleAnnotations` (`javap -v` on a Kotlin 2.4.10 value
  class), so the reader is plain JDK code over the constant pool, with no Kotlin metadata decoder.
  `kotlin.Result` is skipped, and a name the sources declare as an ordinary class wins over a stale
  class file. A missing entry or an unreadable class file is a finding, not a silent pass. Pass the
  compile classpath: `mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt` for Maven, or, for
  Gradle, a task such as the one below (both used in #691), then `--classpath "$(cat cp.txt)"`. On
  the #692 fixture's compile classpath the reader found the `model` module's `CustomerId` plus
  `kotlin-stdlib`'s eight unsigned value classes and `Duration`, and doctor's findings went from 55
  to 56: the extra one is a `UIntArray` parameter, measured lost.

  ```kotlin
  tasks.register("printCompileClasspath") {
      val classpath = sourceSets["main"].compileClasspath
      doLast { println(classpath.asPath) }
  }
  ```
- **`-Xjvm-expose-boxed`.** A `pom.xml` or `build.gradle[.kts]` that passes it applies to the `.kt`
  files under that build file's directory, and there doctor reports only the rows that stay lost
  with the option, with a line saying which build file it read. The option set anywhere else, a
  precompiled `*.gradle.kts` convention plugin for example, is not seen, and there the findings for
  the rows the option would have kept are wrong.

Known misses, every one a false negative, so a clean doctor run is not proof that nothing is lost:

- Value classes declared outside the scanned directory, in another module or a dependency, when
  their jar or class directory is not passed with `--classpath`. On the classpath, a class compiled
  from the pre-1.5 `inline class` form without `@JvmInline`, and a nested value class, are not read.
- A value class reached through a `typealias`, a nested one written as `Outer.Id`, a name declared
  both as a value class and as an ordinary type in the same package, and standard-library value
  classes not on the list above (the unsigned array types, for example).
- Shapes still not built (#713 built the rest): a declaration one level further inside an object
  expression or a local class, a local class's constructor-property accessors, an `override suspend`
  member or a setter inside a value class, and guardrails on local functions.
- A guardrail written through an import alias (`import ...AILocked as Locked`), and source the text
  heuristics misread, such as string templates that nest quotes.

### The strategic risk, stated plainly

Kotlin support rests entirely on kapt. This page previously called kapt "in maintenance mode",
citing three kotlinlang.org pages. **Re-checked on 2026-09-08 against the docs' own source**
([`kapt.md`](https://github.com/JetBrains/kotlin-web-site/blob/master/docs/topics/compiler-plugins/kapt.md)
in `JetBrains/kotlin-web-site`, rather than the rendered page), and none of the three carries that
statement. The kapt page opens with the opposite of a wind-down notice:

> Use **kapt** if:
> * You have a Maven project.
> * You have a Gradle project, but the required Java annotation processor doesn't support KSP yet.

The first bullet is this project's own case. JetBrains does recommend KSP where a processor supports
it, and kapt's stub generation is genuinely expensive, but "recommended against for Gradle projects
whose processors support KSP" is a different claim from "being wound down", and only the first is
sourced. No deprecation has been announced and no removal date exists.

**The structural half of the risk is unchanged, and it is the half that matters.** KSP defines its
own processor interface (`SymbolProcessor`) rather than implementing
`javax.annotation.processing.Processor`, so a JSR 269 processor is not loadable by it. That is not a
policy that might soften; it is how KSP is built. A Kotlin project that has migrated *fully* to KSP
cannot run VibeTags at all, today, and that is already true for teams whose remaining processors
(Hilt, Room, Moshi, Glide) all support KSP and who therefore have no reason to keep the kapt plugin.

So the exposure is real but differently shaped than it was written: not "the mechanism is being
withdrawn", but "the mechanism is fine and a growing share of Kotlin projects no longer load it".
Kotlin support does not degrade gradually in that case, it goes from one level lost to nothing, and
the only in-principle fix is a separate KSP front end reading the same annotations. Issue #496
holds that analysis, including why the compiler-free rendering layer makes it tractable.

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
  `examples/kotlin` on 2.4.10. Nothing here sweeps a range of Kotlin releases. The value-class
  omission, the shape table behind `vibetags doctor` (#692) and the `internal` module suffix were
  measured on 2.4.10 only; the corpus member was not checked for any of them. A Kotlin release that
  changes mangling or kapt's stubs can turn a doctor rule into a false finding.
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
