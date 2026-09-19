# VibeTags: Diagnosing issues

Part of the `vibetags-usage` skill; [SKILL.md](../SKILL.md) holds setup and the element cheat sheet.

## Diagnosing Issues

| Symptom | Cause | Fix |
|---|---|---|
| Green build, no `VibeTags:` line in the compile log at all | The processor never ran: only `vibetags-annotations` is wired up, or JDK 23+ ignored a class-path processor | Put `vibetags-processor` on `annotationProcessorPaths` / the `annotationProcessor` configuration (step 1) |
| Green build, files generated, but not in your project | `VibeTags: Root resolved:` points somewhere else: a Gradle worker, kapt, or an IDE compile | Set `-Avibetags.root` (step 2) |
| `cannot find symbol: class AILocked` | `vibetags-annotations` is missing from the compile classpath | Add it as an ordinary dependency (step 1) |
| Nothing changed after creating a platform file | An incremental build with no changed sources never starts `javac` | `mvn clean compile`, or touch a source file |
| `[NOTE] AGENTS.md left untouched because other AI config files are present` | Working as designed: `AGENTS.md` is managed only when it is the sole AI config file | Paste a `VIBETAGS-START`/`VIBETAGS-END` pair into it to have it managed; otherwise ignore (see step 3) |
| `error: cannot find symbol` … `symbol: method value()` on an `@AI*` annotation | Positional shorthand used on an annotation that has no `value()` element | Use named elements: `@AILocked(reason = "…")`; see the [Element cheat sheet](../SKILL.md#element-cheat-sheet--read-this-before-your-first-annotation) |
| `[WARNING] VibeTags: unrecognized option 'vibetags.…'` | Typo in a `-A` option name | The message lists every supported option; fix the spelling |
| No files updated after compile | Target files don't exist | `touch CLAUDE.md` (or whichever platform file) then recompile |
| `[NOTE] No AI config files found` | No opt-in files present | Create one or more platform files (see step 2) |
| A module's guardrails are missing from the reactor root | That module never reached the root | Give it `-Avibetags.root=<reactor>`; VibeTags warns with *"generated its guardrails as its own root"* when it can tell |
| `[WARNING] … rewritten with a completely different set of elements` | A compilation replaced a module's guardrails with an unrelated set | Almost always a round that could not see the sources it should have — check this module's annotation processing before committing the regenerated files |
| `[WARNING] removed N scoped rule file(s) … while writing only M` | The build deleted more guardrails than it produced | Same cause; do not accept the deletion until you know why |
| `[WARNING] could not identify the compiling module` | Sources are not under `-Avibetags.root` | Set `-Avibetags.root`, or name it with `-Avibetags.module=<name>` |
| Guardrails differ between `mvn compile` and `mvn test` | Pre-1.0.1-RC8 processor | Upgrade — `compile` and `test-compile` now own separate sidecars |
| Gradle appends a second set of `VIBETAGS-MODULE` regions | Pre-1.0.1-RC8 processor | Upgrade, then delete the stray `.vibetags-mod-<hash>` file once |
| `[WARNING] @AIIgnore used but .cursorignore is missing` | Orphaned annotation | Create the missing file to fully support that platform |
| `[WARNING] contradictory @AIDraft and @AILocked` | Both annotations on same element | Remove one of them |
| `[WARNING] @AIAudit has no checkFor items` | Empty `checkFor` array | Add at least one vulnerability string |
| `[WARNING] contradictory @AIContract and @AIDraft` | Both annotations on same element | Remove one — a frozen signature can't also need drafting |
| `[WARNING] overlapping @AIContract and @AILocked` | Both annotations on same element | Use only `@AILocked` if no changes at all are intended |
| `[WARNING] contradictory @AITestDriven and @AIIgnore` | Both annotations on same element | Remove one — `@AIIgnore` excludes the element entirely |
| `[WARNING] contradictory @AITestDriven and @AILocked` | Both annotations on same element | Remove one — `@AILocked` prohibits all changes |
| `[WARNING] @AITestDriven has invalid coverageGoal` | `coverageGoal` outside 0–100 | Set a value between 0 and 100 (inclusive) |
| `[WARNING] @AIImmutable on … but field … is not final` | Non-final, non-static field on `@AIImmutable` class | Make the field `final`, or drop `@AIImmutable` |
| `[WARNING] contradictory @AIDeprecated and @AILocked` | Both annotations on same element | Pick one — locked preserves, deprecated routes callers away |
| `[WARNING] @AIThreadSafe(IMMUTABLE) and @AIImmutable` | Both annotations on same type | Use `@AIImmutable` alone — immutability already implies thread-safety |
| `[WARNING] @AIObservability declares no metrics, traces, or logs` | Empty annotation | Add at least one `metrics`/`traces`/`logs` entry |
| `[WARNING] @AIRegulation has a blank 'standard'` | Required `standard` is empty/whitespace | Name the standard (e.g., `"GDPR"`, `"PCI-DSS"`) |
| `[WARNING] contradictory @AIIdempotent and @AIDraft` | Both annotations on same element | Remove one — idempotent declares a stable contract; draft implies it's unfinished |
| `[WARNING] contradictory @AIFeatureFlag and @AILocked` | Both annotations on same element | Remove one — locked freezes; feature flag implies conditional execution |
| `[WARNING] @AIFeatureFlag has no flag key` | Blank `flag` attribute | Set the flag key (e.g., `flag = "checkout.new-flow"`) |
| `[WARNING] @AISecure has no aspect` | Blank `aspect` attribute | Specify the security concern (e.g., `aspect = "authentication"`) |
| `[WARNING] contradictory @AISecure and @AIIgnore` | Both annotations on same element | Remove `@AIIgnore` — security-critical code must remain visible to AI for review |
| `[WARNING] contradictory @AISandboxOnly and @AIDomainModel` | Both annotations on same element | Sandbox mocks should not be subjected to framework-free domain model constraints |
| `[WARNING] contradictory @AISunset and @AIDraft` | Both annotations on same element | Sunset elements must not be actively drafted or expanded |
| `[WARNING] redundant @AISecureLogging and @AIIgnore` | Both annotations on same element | `@AIIgnore` already completely excludes this element; `@AISecureLogging` is redundant |
| `[WARNING] @AISunset has a blank 'jira'` | Blank `jira` attribute | Specify the JIRA issue ticket key (e.g., `jira = "DEBT-123"`) |
| `[WARNING] @AITemporary has a blank 'expiresOn'` | Blank `expiresOn` attribute | Specify an ISO date (`expiresOn = "YYYY-MM-DD"`) |
| `[WARNING] @AITemporary has an invalid 'expiresOn' date format` | Format not YYYY-MM-DD | Use strict `YYYY-MM-DD` syntax (e.g., `"2026-06-30"`) |
| `[WARNING] Temporary logic in … has expired` | Current date is past `expiresOn` | The temporary hotfix/hack has expired; clean it up immediately |
| `[WARNING] @AIArchitecture has a blank 'belongsTo'` | Blank `belongsTo` layer | Specify the layer name (e.g., `belongsTo = "domain"`) |
