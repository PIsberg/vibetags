---
name: add-annotation
description: Add a new @AI... guardrail annotation to VibeTags — wire it through AnnotationCollector, every platform renderer, GranularRenderer, BuildFingerprint, and AnnotationValidator, then update all four docs. Use when the user says "add annotation", "new @AI annotation", "add a new guardrail annotation", or wants VibeTags to support a new kind of AI instruction on Java code.
---

# Add an Annotation to VibeTags

Paths and class names below were derived from the codebase at v1.0.0-RC3 (the `NewAnnotationsV5`
wave — `@AIIdempotent`, `@AIFeatureFlag`, `@AISecure` — plus commit `df01cb8`, which fixed
`BuildFingerprint` silently ignoring 12 annotation buckets and is why Step 5 below is not
optional). If a path 404s, `grep -rln "AISecure" vibetags/src/main/java` from the repo root will
re-locate every dispatch point this skill lists — `@AISecure` is the newest full annotation and
touches every one of them.

## Step 1 — Design the annotation

New file: `vibetags-annotations/src/main/java/se/deversity/vibetags/annotations/AIYourName.java`.

- `@Retention(RetentionPolicy.SOURCE)` always — zero runtime cost is a hard invariant of this
  library; nothing about the annotation may require `CLASS` or `RUNTIME` retention.
- `@Target({...})` — pick from `TYPE`, `METHOD`, `FIELD`, `PARAMETER` to match the semantic
  (e.g. `@AIInputSanitized`/`@AISecureLogging` target `PARAMETER, FIELD`, not `TYPE`).
- String attributes default to `""`, never `null` — every consumption site checks
  `.isBlank()`/`.isEmpty()`, never a null-check.
- Enum-valued attributes get a nested `enum` inside the `@interface` with a sensible default
  constant (see `AIThreadSafe.Strategy`, `AIExtensible.Strategy`, `AISecureLogging.MaskingPolicy`).
- Class-valued attributes (`AISunset.replacement`) must be read via `MirroredTypeException` at
  every consumption site — copy the try/catch pattern from `GranularRenderer.renderGranular()`
  and `BuildFingerprint.compute()`.
- Full Javadoc on the `@interface` and every attribute — `docs/ANNOTATIONS.md`'s semantics bullet
  and the `vibetags-usage` skill's per-annotation section are meant to summarize it, not invent it.

## Step 2 — Wire the collection pipeline

`vibetags/.../internal/AnnotationCollector.java` — six edits in this one file:

1. `private final Set<Element> yourNameElements = new LinkedHashSet<>();` — **must** be
   `LinkedHashSet`; insertion order feeds `BuildFingerprint` determinism (a class-level
   `@AIContext` javadoc on this file explains why `HashSet` is a correctness bug, not a style
   nit).
2. `collectInto(yourNameElements, AIYourName.class, roundEnv, presentAnnotationFqns);` in
   `collect(...)`.
3. OR it into the `boolean added = ... || !yourNameElements.isEmpty();` expression right after.
4. `yourNameElements.clear();` in `reset()`.
5. `public Set<Element> yourName() { return Collections.unmodifiableSet(yourNameElements); }`.
6. `labeled.put("@AIYourName", yourName());` in `labeledSets()` — `logSummary()` iterates this
   map, so no separate edit is needed there — and add its `.size()` to the sum in
   `totalAnnotatedReferences()` (a `StringBuilder` pre-sizing hint; harmless to miss but keep it
   complete).

## Step 3 — Formatter (one class, dispatches to every platform)

`vibetags/.../internal/content/annotations/AIYourNameFormatter.java implements
AnnotationFormatter`:

- One `case PLATFORM:` per platform whose renderer should show this annotation — copy
  `AISecureFormatter`'s shape (a `switch (platform)` producing platform-appropriate syntax:
  Markdown bullet, XML element, YAML/TOML/JSON fragment). Platforms you don't handle fall through
  to `default: break;` — silent omission, not an error.
- Register it: add a `private static final AIYourNameFormatter YOUR_NAME = new
  AIYourNameFormatter();` field and `public static AIYourNameFormatter yourName() { return
  YOUR_NAME; }` getter to `FormatterRegistry`.

## Step 4 — Wire every renderer that needs to show it

Two families, different plumbing:

**A. Markdown bucket-walk renderers** (Cursor, Windsurf, Zed, Copilot, Qwen, Codex, Gemini) —
each owns its own `SECTIONS` list of `AnnotationSections.Section`. Add one line to each of the
7 files: `section(Platform.X, SectionCatalog.Key.YOUR_NAME, AnnotationCollector::yourName,
FormatterRegistry.yourName())`. If a platform instead folds the newest annotations into a shared
trailing list (Cursor/Windsurf both reuse `AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS`),
add there instead of duplicating a per-file entry.

**B. `ClaudeRenderer` (bespoke XML)** — hand-add a block matching the ~35 already there:
```java
if (!collector.yourName().isEmpty()) {
    StringBuilder sec = new StringBuilder("  <your_name_elements>\n");
    for (Element e : collector.yourName()) {
        FormatterRegistry.yourName().format(e, sec, Platform.CLAUDE);
    }
    sec.append("  </your_name_elements>\n");
    sb.append(sec);
    sb.append("\n<rule>...guardrail description...</rule>\n");
}
```

**C. `SectionCatalog.java`** — add a `Key.YOUR_NAME` enum constant, then
`DEFAULT.put(Key.YOUR_NAME, "\n## <emoji> HEADING\n<one-line description>\n\n");` (this is what
Cursor/Windsurf pull by default). Add `OVERRIDES` entries under the existing
`windsurfOverrides`/`zedOverrides`/`copilotOverrides`/`qwenOverrides`/`codexOverrides`/
`geminiOverrides` maps only if that platform's wording should diverge from `DEFAULT` — otherwise
it inherits `DEFAULT` automatically via `SectionCatalog.header()`'s fallback.

**D. `GranularRenderer.renderGranular()`** — add a
`for (Element e : collector.yourName()) { ... appendToGranular(elementRules, e, "Section Title",
"- **Field**: " + a.field()); }` block, or the annotation never appears in any granular rule file
even while granular platforms are active — this loop is not driven by the SECTIONS lists above.

**E. Every other renderer that walks `collector.xxx()` directly** (`LlmsRenderer`,
`AiderConventionsRenderer`, etc., plus any bespoke platform renderer from a previous
`add-platform` pass) — run `grep -rn "collector\.secure()" vibetags/src/main/java/.../content/
platforms/` (or any other recent annotation's accessor) to enumerate every call site that needs a
sibling for your new accessor.

## Step 5 — BuildFingerprint (do not skip this)

`vibetags/.../internal/BuildFingerprint.java` — add one
`appendAnnotationSet(sb, "XX", collector.yourName(), e -> { ... join every attribute that affects
rendered output with a delimiter ... });` call, `"XX"` a short tag not already used by a sibling
call. Skipping this means editing the annotation's attributes on an already-annotated element
does not change the fingerprint, so the top-level short-circuit in `generateFiles()` (locked —
see the class's `@AILocked` javadoc) serves stale output on the next compile without any error.
This exact regression shipped once and was fixed in commit `df01cb8`
("fingerprint ignores the 12 newest annotation buckets").

## Step 6 — AnnotationValidator (only if there is something to validate)

`vibetags/.../internal/AnnotationValidator.java` — add a
`for (Element element : elementsOf(roundEnv, present, AIYourName.class)) { ... }` block per check:
contradictory pairing (`element.getAnnotation(Other.class) != null`), redundant pairing,
blank-required-attribute, or invalid-value-range. Follow the existing message shape exactly:
`"VibeTags: " + element + " is annotated with both @X and @Y. <why it's contradictory>."` for
pairings, `"VibeTags: @X on " + element + " has a blank '<attr>' attribute. <what to do>."` for
missing values. Not every annotation needs a validator — skip this step if there is genuinely
nothing to check (e.g. a marker annotation whose only attribute is a free-form `reason`).

## Step 7 — Tests (three new files, following the current wave's naming)

Name them `NewAnnotationsV<N>DefinitionTest` / `...EndToEndTest` / `...ValidationTest` (bump `N`
past the last wave — currently `V5`) unless you're extending an in-flight wave:

- **DefinitionTest** — reflection only: `@Retention(SOURCE)`, `@Target(...)` array equality, and
  each attribute's default via `AIYourName.class.getDeclaredMethod("attr").getDefaultValue()`.
- **EndToEndTest** — annotate a class in the example/test fixtures, run the processor via
  `ProcessorTestHarness`, assert the rendered content appears with correct syntax in every
  platform file it should reach (markdown bullet, XML element, granular rule file, `llms.txt`,
  etc.).
- **ValidationTest** — one test per warning added in Step 6, asserting the `Messager` receives it
  for the contradictory/invalid case and stays silent when the condition isn't met.

## Step 8 — Docs (four places, nothing auto-propagates)

- **`docs/ANNOTATIONS.md`** — table row + an "Annotation semantics" bullet + any new
  "Compile-time validation warnings" bullets from Step 6.
- **`README.md`** — bump `**N annotations**` in the project-facts line.
  `ProjectFactsConsistencyTest` counts `.java` files under `vibetags-annotations/.../annotations/`
  containing the literal text `public @interface` and fails the build if that count and the
  README figure disagree — no manual sync-checking needed, just make the number right.
- **`USAGE.md`** — add a `### @AIYourName — <title>` subsection under a "New in vX.Y.Z" heading,
  following the existing per-wave sections (e.g. "Design-Intent Annotations (v0.9.8)"), plus a
  Contents link near the top of the file.
- **`.claude/skills/vibetags-usage/SKILL.md`** — add `@AIYourName` to the frontmatter
  `description` trigger list, a full `### \`@AIYourName\`` section in "Annotations Reference"
  (use/example/semantics/warnings, matching the existing entries' shape), a row in "Annotation
  Combinations" if it interacts with others, a row in "Diagnosing Issues" if Step 6 added a
  warning, and — if genuinely new rather than a format addition — the annotation belongs in every
  count/list this skill maintains for the other 38.

## Verify

```bash
cd vibetags-annotations && mvn install && cd ..
cd vibetags && mvn clean install && cd ..
cd vibetags && mvn test -Dtest=NewAnnotationsV<N>DefinitionTest,NewAnnotationsV<N>EndToEndTest,NewAnnotationsV<N>ValidationTest,BuildFingerprintUnitTest,BuildFingerprintIntegrationTest,ProjectFactsConsistencyTest && cd ..
cd vibetags-bom && mvn install && cd ..
cd example && mvn clean compile && cd ..
```

`@SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")` on `AIGuardrailProcessor`
means the new annotation is picked up by javac automatically — there is no SPI-level registration
step for the annotation itself, only the dispatch points above.
