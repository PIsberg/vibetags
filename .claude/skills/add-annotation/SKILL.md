---
name: add-annotation
description: Add a new @AI... guardrail annotation to VibeTags — register it in GuardrailAnnotations, then wire it through every platform renderer, GranularRenderer, BuildFingerprint, and AnnotationValidator, and update all four docs. Use when the user says "add annotation", "new @AI annotation", "add a new guardrail annotation", or wants VibeTags to support a new kind of AI instruction on Java code.
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
- Class-valued attributes (`AISunset.replacement`) cannot be read below the model seam at all:
  calling them during annotation processing throws `MirroredTypeException`. Resolve the value
  **once**, in `AnnotationCollector.record(...)` — the only place the compiler is still in scope —
  and store it with `builder.typeMember("AIYourName.attr", …)`. Consumption sites then read
  `element.typeMember("AIYourName.attr", "<fallback>")`. Copy the `AISunset` pattern; do not
  reintroduce a try/catch in a formatter or renderer, since `ArchitectureRulesTest` forbids
  `javax.lang.model` there and the catch would never fire anyway.
- Full Javadoc on the `@interface` and every attribute — `docs/ANNOTATIONS.md`'s semantics bullet
  and the `vibetags-usage` skill's per-annotation section are meant to summarize it, not invent it.

## Step 2 — Wire the collection pipeline

**One line**, in `vibetags/.../processor/model/GuardrailAnnotations.java`: append
`AIYourName.class` to the end of `ALL`.

That registry drives collection, reset, the label map, the size sum, and the model's buckets —
`AnnotationCollector` and `GuardrailModel` both iterate it, so there is nothing else to edit here.
**Append, never reorder**: the list order fixes the insertion order of every `LinkedHashSet`
downstream, and reordering changes generated files for every consumer.

Then add the two named accessors that renderers read as method references:

- `GuardrailModel`: `public Set<TaggedElement> yourName() { return of(AIYourName.class); }`
- `AnnotationCollector`: `public Set<Element> yourName() { return elementsOf(AIYourName.class); }`
  — the javac-side view, used by tests and by anything that needs a real `Element`.

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
7 files: `section(Platform.X, SectionCatalog.Key.YOUR_NAME, GuardrailModel::yourName,
FormatterRegistry.yourName())`. If a platform instead folds the newest annotations into a shared
trailing list (Cursor/Windsurf both reuse `AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS`),
add there instead of duplicating a per-file entry.

**B. `ClaudeRenderer` (bespoke XML)** — hand-add a block matching the ~35 already there:
```java
if (!model.yourName().isEmpty()) {
    StringBuilder sec = new StringBuilder("  <your_name_elements>\n");
    for (TaggedElement e : model.yourName()) {
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
`for (TaggedElement e : model.yourName()) { ... appendToGranular(elementRules, e, "Section Title",
"- **Field**: " + a.field()); }` block, or the annotation never appears in any granular rule file
even while granular platforms are active — this loop is not driven by the SECTIONS lists above.

**E. Every other renderer that walks `model.xxx()` directly** (`LlmsRenderer`,
`AiderConventionsRenderer`, etc., plus any bespoke platform renderer from a previous
`add-platform` pass) — run `grep -rn "model\.secure()" vibetags/src/main/java/.../content/
platforms/` (or any other recent annotation's accessor) to enumerate every call site that needs a
sibling for your new accessor.

## Step 5 — BuildFingerprint (do not skip this)

`vibetags/.../internal/BuildFingerprint.java` — add one
`appendAnnotationSet(sb, "XX", model.yourName(), e -> { ... join every attribute that affects
rendered output with a delimiter ... });` call, `"XX"` a short tag not already used by a sibling
call. Skipping this means editing the annotation's attributes on an already-annotated element
does not change the fingerprint, so the top-level short-circuit in `generateFiles()` (locked —
see the class's `@AILocked` javadoc) serves stale output on the next compile without any error.
This exact regression shipped once and was fixed in commit `df01cb8`
("fingerprint ignores the 12 newest annotation buckets").

## Step 6 — Validation rules (only if there is something to validate)

Rules live in `vibetags/.../internal/validation/`. **Do not add a loop to `AnnotationValidator`** —
it is a thin entry point now, and a rule written there would be outside the registry that groups
round queries by annotation.

- **Contradictory or redundant pairing** → one line in `ValidationRules.PAIRS`:
  `PairRule.warn(AIYourName.class, Other.class, " is annotated with both @AIYourName and @Other. <why>.")`.
  The message is the text that follows the element's name, leading space included, and `warn`'s
  first argument decides which round query runs — make it the rarer annotation.
- **Blank required attribute or invalid value range** → an `AttributeRule.of(...)` entry in
  `CoreRules.all()`.
- **The annotation contradicts the declaration it sits on** (a record, a sealed or final type, a
  `void` method, a non-public element) → `ModernJavaRules`.

Follow the existing message shape exactly:
`"VibeTags: " + element + " is annotated with both @X and @Y. <why it's contradictory>."` for
pairings, `"VibeTags: @X on " + element + " has a blank '<attr>' attribute. <what to do>."` for
missing values — `ValidationContext` adds the `VibeTags: ` prefix, so rule bodies do not.
Not every annotation needs a validator — skip this step if there is genuinely nothing to check
(e.g. a marker annotation whose only attribute is a free-form `reason`).

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
- **`.claude/skills/vibetags-usage/SKILL.md`, "Element cheat sheet"** — a row in the "Every
  element, in full" table listing every element (bold the ones with no default), plus the
  positional-form table if it declares `value()` and the "will not compile bare" list if any
  element has no default. The word-numbers introducing those two lists are counts and move too.
  `SkillElementTableConsistencyTest` checks all of this against the annotation source, so you do
  not have to remember it — but you do have to make the build green before pushing.

## Verify

```bash
cd vibetags-annotations && mvn install && cd ..
cd vibetags && mvn clean install && cd ..
cd vibetags && mvn test -Dtest=NewAnnotationsV<N>DefinitionTest,NewAnnotationsV<N>EndToEndTest,NewAnnotationsV<N>ValidationTest,BuildFingerprintUnitTest,BuildFingerprintIntegrationTest,ProjectFactsConsistencyTest && cd ..
cd vibetags-bom && mvn install && cd ..
cd examples/basic && mvn clean compile && cd ../..
```

`@SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")` on `AIGuardrailProcessor`
means the new annotation is picked up by javac automatically — there is no SPI-level registration
step for the annotation itself, only the dispatch points above.
