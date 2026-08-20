# VibeTags Scala Example (Java sources only)

This example demonstrates a **limitation** as carefully as a feature: scalac has no
JSR 269 support, so the VibeTags processor never sees Scala sources.

Two annotated classes:

- `src/main/java/.../AuditLog.java` — compiled by javac, **appears** in the generated
  guardrail files. This is the supported pattern for Scala codebases: put guardrails on
  thin annotated Java types next to the Scala code they protect.
- `src/main/scala/.../ReportEngine.scala` — carries `@AILocked`, compiles without a
  warning, and **never appears** in any generated file. CI asserts the negative: if
  `ReportEngine` ever shows up in `CLAUDE.md` or `.cursorrules`, the build goes red,
  because that would mean the documentation above is wrong.

## Build

The VibeTags artifacts must be installed locally first (see the repository README), then:

```bash
./gradlew clean build
```

## If you need guardrails on Scala code

Either the annotated-Java-neighbour pattern above, or write the rule by hand *outside*
the `VIBETAGS-START` / `VIBETAGS-END` markers — everything outside the markers is yours
and survives every regeneration. There is no kapt equivalent for Scala; this is a
compile-model gap, not a missing VibeTags feature.
