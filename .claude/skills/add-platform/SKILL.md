---
name: add-platform
description: Add support for a new AI coding tool, IDE, PR-reviewer, or context-packer to VibeTags — wire a new generated output file through ServiceRegistry, Platform, the renderer registry, and (if granular) GranularRulesWriter. Use when the user says "add platform support", "support <tool>", "generate a new output file for X", "new AI platform", or names an AI coding assistant/IDE/PR-reviewer/context-packer VibeTags doesn't yet generate a file for.
---

# Add an AI Platform to VibeTags

Paths and class names below were derived from the codebase at v1.0.0-RC3 (commits `2bc839e`
Claude Code local/granular/Skill + Copilot granular, `9c61a83` AI PR-reviewers/context-packers/
Void/Roo, `406f353` Firebase AI — the smallest recent example). If a path below 404s, the
architecture has drifted since — `grep -rn "FIREBASE" vibetags/src/main/java` from the repo root
will re-locate every dispatch point this skill lists.

## Step 0a — Verify the path at the vendor, before anything else

**Every** candidate path checked against a vendor's own docs rather than a cross-tool round-up has
so far turned out wrong or stale: Warp, Antigravity, OpenHands, Kilo and Crush in the 2026-09-08
sweep (#611); `.aiignore`, `.cursorindexingignore`, `.clineignore` and `.continuerules` in the
2026-09-12 one (#638, #640). Nine for nine. Read the vendor's page before writing a line.

- If the docs page is a JS app, behind a login, or 404s, get the shape from **real files**:
  `gh api -X GET search/code -f q='filename:X' --jq '.items[].repository.full_name'`, then
  `curl -sL https://raw.githubusercontent.com/<repo>/HEAD/<path>`. That is how Zencoder's front
  matter (`description` + `alwaysApply`) was settled rather than guessed.
- **Adoption prompts the look and never settles it.** `.aiignore` sits in 820 repositories and is
  worth nothing, because JetBrains states that a project with `.cursorignore`, `.codeiumignore` or
  `.aiexclude` needs no `.aiignore` — and VibeTags writes all three. Ask what the tool *reads*, not
  what people have.
- Check the product is alive. Cody retired 2025-07-23, Supermaven 2025-11. Nothing in the build can
  notice a vendor sunsetting a product (#641).
- If the path is reached by a file VibeTags already writes (`AGENTS.md`, `.cursorrules`,
  `CLAUDE.md`), a second copy of the same content is not reach, it is duplication. Say so and stop.

## Step 0 — Decide the shape before touching code

- **New platform or new format of an existing one?** Claude Code's local-override/granular/Skill
  additions were new *formats* of the already-listed "Claude" platform, so the README's `**N AI
  platforms**` count did not move. Only a genuinely new platform bumps that count.
- **Marker style is not your choice** — `GuardrailFileWriter.getMarkersFor(fileName)` derives it
  from the extension: `.md`/`.mdc`/`llms.txt`/`llms-full.txt` → HTML comment markers; `.json`/
  `.toml` → full overwrite, no markers; everything else → hash markers. Multi-module sidecar
  merge in `generateFiles()` is gated solely on "does this file get markers" — you do not write
  any sidecar-participation code either way.
- **Granular** (one file per annotated class)? → goes through `GranularRulesWriter`, not a
  single-file `PlatformRenderer`.
- **Ignore-file family** (a glob-pattern exclusion list driven only by `@AIIgnore`)? → reuse
  `IgnoreFileRenderer`, don't write a new renderer class.
- **Byte-identical output to an existing renderer**? → delegate, don't reimplement (see
  `FirebaseRenderer`, which wraps a shared `CursorRenderer` instance).
- **Implicitly-activated sidecar of another service** (`codex_config`/`codex_rules` under
  `codex`, `qwen_settings`/`qwen_refactor` under `qwen`, `cody` under `cody`)? → do **not** add
  its own key to `ServiceRegistry.OPT_IN_KEYS`; wire it into the special-case block at the bottom
  of `GuardrailContentBuilder.build()` instead.

## Checklist (file by file)

1. **`vibetags/src/main/java/se/deversity/vibetags/processor/internal/content/Platform.java`**
   — add `YOUR_PLATFORM("your_platform")` to the enum. The string is the service key used
   everywhere else below.

2. **`vibetags/.../internal/ServiceRegistry.java`** — two edits:
   - `buildServiceFileMap()`: `map.put("your_platform", root.resolve("<relative/path>"));`
     (a directory `Path` for granular platforms, a file `Path` otherwise).
   - `OPT_IN_KEYS`: add the key(s) whose file/directory presence should activate the service.
     Skip implicitly-derived keys (Step 0, last bullet) — they activate via their parent's key.

3. **Renderer** — `vibetags/.../internal/content/platforms/<Name>Renderer.java implements
   PlatformRenderer`:
   - *Markdown bucket-walk* (Cursor/Windsurf/Zed/Copilot/Qwen/Codex/Gemini style): build a
     `private static final List<AnnotationSections.Section> SECTIONS` using
     `AnnotationSections.section(Platform.X, SectionCatalog.Key.K, GuardrailModel::accessor,
     FormatterRegistry.formatter())` per annotation bucket, then `render()` calls
     `AnnotationSections.render(sb, model, Platform.X, SECTIONS)`. Reuse
     `AnnotationSections.renderLockedAndContextPreamble(...)` for the opening if your preamble
     matches Cursor/Windsurf's "LOCKED FILES / CONTEXTUAL RULES" shape.
   - *Bespoke structured format* (XML/YAML/TOML/JSON): hand-roll like `ClaudeRenderer` /
     `CodeRabbitRenderer` / `PrAgentRenderer` — walk `model.xxx()` sets directly and call
     `FormatterRegistry.xxx().format(e, sb, platform)` per element. Every `AnnotationFormatter`
     you rely on then needs a `case YOUR_PLATFORM:` (Step 5).
   - *Ignore-only file*: don't write a renderer — add a `case YOUR_IGNORE:` to
     `IgnoreFileRenderer.getPlatformSpecificName()` and route the Platform enum constant to
     `IGNORE_FILE_RENDERER` in the registry (Step 4). **Then add the same case to
     `AIIgnoreFormatter`'s glob branch.** Its `default` arm writes nothing, so an ignore file wired
     through `ServiceRegistry` and the registry but missed there is created, opted into, and left
     holding a header with no globs under it: nothing thrown, nothing logged, and an existence check
     green. Assert the glob in the test, never the file.
   - *Delegating*: wrap and forward to the existing renderer's `render()` (`FirebaseRenderer`).
   - **YAML output** — also override `mergeShape()`. A YAML document has one of each top-level key,
     and the multi-module merge stacks whole renderings unless told otherwise, so without this the
     file gets its `rules:` / `reviews:` / `customModes:` repeated once per module: invalid to a
     strict parser, silently truncated to the last module by a lenient one. Declare the last line of
     your shared scaffold, the column your entries sit at, and what you emit when there is nothing
     to say — `SweepRenderer` (sequence), `CodeRabbitRenderer` (block scalar) and `PlandexRenderer`
     (conditional buckets → `YamlMergeShape.keyed`) are the three worked examples.
     `YamlMergeShapeContractTest` fails the build if you skip it or if the declaration drifts from
     what the renderer writes.
   - **JSON or TOML output whose content varies with the annotations** — override
     `wholeFileMerge()` instead. Those files carry no markers, so they are whole-file overwrites and
     without a merge a reactor publishes whichever module compiled last. `MentatRenderer`
     (`WholeFileMerge.jsonRules()`) and `PrAgentRenderer` (`WholeFileMerge.tomlInstructions()`) are
     the worked examples; a static config that does not vary needs nothing.
     `MultiModuleWholeFileMergeTest` derives the requirement by rendering your service empty and
     populated, so forgetting it fails the build.

4. **`vibetags/.../internal/content/PlatformRendererRegistry.java`** — declare
   `private static final XRenderer X_RENDERER = new XRenderer();` and add `case X:` (multiple
   `case`s can fall through to one renderer, e.g. `CODEX`/`CODEX_CONFIG`/`CODEX_RULES`) in
   `findRenderer()`. Granular platforms map to the existing `GRANULAR_RENDERER`.

5. If your renderer walks per-annotation buckets, every `AnnotationFormatter` under
   `internal/content/annotations/*Formatter.java` needs a `case YOUR_PLATFORM:` in its
   `switch (platform)` (see `AISecureFormatter`) or that annotation silently renders nothing for
   your platform. Run `grep -rln "case CURSOR:" vibetags/src/main/java/.../content/annotations/`
   to enumerate every formatter switch that may need a sibling case — add one to each formatter
   whose annotation your platform should surface.

6. **`SectionCatalog.java`** — only if your renderer uses `AnnotationSections.section()` **and**
   your platform's heading text should differ from the Cursor/`DEFAULT` wording: add an
   `OVERRIDES.put(Platform.X, ...)` map (see the `windsurfOverrides`/`zedOverrides`/
   `copilotOverrides`/`qwenOverrides`/`codexOverrides`/`geminiOverrides` blocks for the pattern),
   and/or a `HEADERLESS.put(Platform.X, EnumSet.of(...))` entry for buckets folded into the
   previous section with no heading of their own. Omit both to inherit `DEFAULT` verbatim.

7. **Granular only** — `vibetags/.../internal/GranularRulesWriter.java`:
   - add `boolean xGranular = activeServices.contains("x_granular");` and OR it into the early
     `if (!cursorGranular && ... ) return writtenQNames;` gate
   - inside `elementRules.forEach(...)`, add a write branch building the file's front-matter
     shape — YAML `globs:`/`description:`/`alwaysApply:` (Cursor/Windsurf/Trae/Continue/PearAI),
     YAML `paths:` (Claude), a single-string `applyTo:` (Copilot), or no front-matter at all
     (Roo/Tabnine/AmazonQ/Kiro/`.ai/rules`) — then
     `fileWriter.writeFileIfChanged(serviceFiles.get("x_granular").resolve(qName +
     ".<ext>").toString(), md, true);`
   - add the matching line to `cleanupAll()` with the **exact same extension string** — a
     mismatch there means orphan cleanup either no-ops or scrubs the wrong files (this class was
     already bitten once: `cleanupGranularDirectory` used to mis-parse multi-dot extensions like
     `.instructions.md` via `lastIndexOf('.')`; it now strips the known suffix instead).

8. **`OrphanWarner.java`** — usually skip. Only touch it if the new platform is a paired
   ignore/hard-guardrail file that should warn when `@AIIgnore`/`@AILocked` is used without it
   (mirrors the existing cursor/claude/copilot/qwen/`aiexclude` pattern).

9. **Two hardcoded test service-key sets** — both must include your new key(s) or the whole
   file fails on an unrelated assertion:
   - `AIGuardrailProcessorProcessTest.java` — `expectedKeys = Set.of(...)`
   - `AIGuardrailProcessorUnitTest.java` — the matching `Set.of(...)`

10. **New end-to-end test** — add to (or start the next) `NewPlatformsV<N>EndToEndTest.java`
    following `NewPlatformsV4EndToEndTest`'s shape: `ProcessorTestHarness.withExampleSources
    (tempDir)` in `@BeforeAll`; one `testAllNewFilesExist()`; then per-file content tests
    asserting the generated header, the correct marker pair (or none), that an `@AIIgnore`/
    `@AILocked` element from the example sources actually appears, and — for structured formats
    — that a real parser accepts the output (the v0.9.9 PR-reviewer wave caught real bugs this
    way: escaped-quote and trailing-comma failures a string-contains assertion would have missed).

11. **`examples/basic/` opt-in fixture** — create the empty placeholder (`touch examples/basic/<path>`, or an
    empty directory for granular) so the example build actually exercises the platform, and add
    it to the `AI_FILES` array in `examples/basic/reset-ai-files.sh` (plus the granular cleanup `for dir
    in ...` loop near the bottom of that script if it's directory-based).

12. **Docs — four places, none auto-propagate:**
    - `docs/PLATFORMS.md` — add the file / platform / format table row
    - `USAGE.md` — the `touch`/`mkdir -p` opt-in block (the root `CLAUDE.md` no longer carries a
      second copy of the output table; it links to `docs/PLATFORMS.md`)
    - `README.md` — add a `- **Name** — ...` bullet under `### Supported AI Platforms` **only if
      this is a new platform**, not a new format of one already listed; if you do, bump the
      `**N AI platforms**` figure in the project-facts line to match — `ProjectFactsConsistencyTest`
      fails the build if the stated count and the distinct-bullet count disagree (Cursor and
      Windsurf are deliberately counted once each despite appearing under two formats)
    - `.claude/skills/vibetags-usage/SKILL.md` — add the `touch`/`mkdir -p` line to the Quick
      Setup block and a row to "Supported Output Files"

## Three gates no Maven build runs

`mvn verify -Pe2e` green does not mean CI is green. Each of these has failed a platform PR after a
clean local suite:

1. **`.github/actions/verify-generated-files`** — a hardcoded file list plus content greps, run
   against `examples/basic`. Add the new file to the list, and prefer a **content** assertion over
   an existence one: `CONVENTIONS.md` existed and was non-empty for eleven releases while aider
   never opened it, so "exists" is the property that was true throughout the defect. **Run the
   action's body, do not read it** — extract every `run: |` block into a file and
   `bash -e -o pipefail` it from `examples/basic`. A hand edit put a literal backslash-n into that
   list and turned the loop into `FAIL n missing` across every Maven, Gradle and cross-platform job.
2. **Architecture Diagram Drift** — fingerprints which types exist, so *any new renderer class*
   makes the committed SVGs stale. Do not reproduce the code-karta toolchain: the job uploads a
   `regenerated-diagrams` artifact. `gh run download <run> -n regenerated-diagrams -D /tmp/diag`,
   copy the five SVGs over `docs/diagrams/codekarta/`, commit. Budget one CI round-trip for it.
3. **Static analysis** — `mvn test` runs none of it. Use **`mvn -B verify -Pe2e`**; PMD, CPD and
   SpotBugs bind to `verify`, and PMD has caught `InefficientStringBuffering` in new renderer code
   before.

## Extend the multi-module examples too, when the shape calls for it

`examples/basic` is the exhaustive fixture and `ExampleOptInCoverageTest` enforces it. The others
carry deliberate subsets (`examples/INDEX.md` is the ledger), so do not add a new file to all of
them by reflex. One case does call for it: **a YAML platform with a `mergeShape()` belongs in
`examples/multimodule` and `examples/gradle-multimodule`**, which already opt into every other one
(`.coderabbit.yaml`, `sweep.yaml`, `.plandex.yaml`, `.roomodes`). Those are the byte-for-byte drift
gate for the merge, and the unit tests do not cover the re-emit path: `.aider.conf.yml` shipped with
a `strip()`ped `emptyBody` that dedented the sequence to column 0 in a reactor and column 2 in a
single module, and only building the multi-module example showed it.

## Verify

```bash
cd vibetags-annotations && mvn install && cd ..
cd vibetags && mvn clean install && cd ..
cd vibetags-bom && mvn install && cd ..
cd vibetags && mvn test -Dtest=NewPlatformsV<N>EndToEndTest,AIGuardrailProcessorProcessTest,AIGuardrailProcessorUnitTest && cd ..
cd examples/basic && mvn clean compile && cd ..   # real end-to-end: consumes the freshly installed processor
```

Confirm the new file (or directory of granular files) actually appeared under `examples/basic/` with
real generated content, then run `pre-commit run --all-files` before committing.
