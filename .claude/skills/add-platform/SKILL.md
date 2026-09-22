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
- Check the product is alive, at the vendor, not in a round-up. Cody Free and Pro ended 2025-07-23
  while Cody Enterprise continued; Supermaven announced its sunset 2025-11-21 but kept autocomplete
  for existing JetBrains and Neovim users (#677). Quote what the source says, no more. Nothing in the
  build can notice a vendor sunsetting a product (#641).
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
- **Byte-identical output to an existing renderer**? → write no class at all: add the platform
  as a fall-through `case` beside that renderer in `PlatformRendererRegistry.findRenderer`, with a
  comment line saying what the file is (see `FIREBASE`, `GOOSE`, `REPLIT` beside `CURSOR`, #764).
- **Implicitly-activated sidecar of another service** (`codex_config`/`codex_rules` under
  `codex`, `cline_safety` under `cline_granular`)? → do **not** add
  its own key to `ServiceRegistry.OPT_IN_KEYS`; wire it into the special-case block at the bottom
  of `GuardrailContentBuilder.build()` instead.

## Checklist (file by file)

1. **`vibetags/src/main/java/se/deversity/vibetags/processor/internal/content/Platform.java`**
   — add `YOUR_PLATFORM("your_platform")` to the enum. The string is the service key used
   everywhere else below.

2. **`vibetags/.../internal/content/PlatformDescriptors.java`** — append one
   `new PlatformDescriptor(...)` entry **at the end** of `ALL`, and nothing else:

   | column | what goes in it |
   |---|---|
   | `serviceKey` | the string from Step 1 |
   | `relativePath` | root-relative, `/` separators, the directory itself for a granular platform |
   | `kind` | `Kind.FILE` or `Kind.DIRECTORY` |
   | `implicitParent` | the key whose activation activates this one, or `null` when its own presence on disk is the opt-in (Step 0, last bullet) |
   | `platform` | `Platform.YOUR_PLATFORM` |
   | `renderer` | the singleton from Step 3 |
   | `ignoreLabel` | the tool's display name in an exclusion file's header, or `null` |
   | `globSyntax` | `true` when `@AIIgnore` writes bare `.gitignore` globs into it |

   That entry is the service map, the opt-in set, the file-or-directory answer, the renderer lookup
   and both exclusion-file lists at once (#762), so `ServiceRegistry`, `PlatformRendererRegistry`,
   `IgnoreFileRenderer` and `AIIgnoreFormatter` need no edit. Carry the reason your platform is
   unusual into a comment on the entry: the entry is where the next reader will look.

   Append, never insert or reorder. The order of `ALL` is the order `buildServiceFileMap` returns,
   which is the order of the "create one of these files" note a new user copies from and of merge
   and log iteration across a reactor. Add your row to the end of `PlatformDescriptorsTest.PINNED`
   too, or that test fails and names it.

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
     you rely on then needs a `case YOUR_PLATFORM:` (Step 5). The exception is a platform that
     prints another one's words verbatim: make it an alias in `Platform.rendersAs()` instead, and
     no formatter or `SectionCatalog` entry is needed at all (#764). Add it to
     `PlatformAliasTest.ALIASES`.
   - *Ignore-only file*: don't write a renderer — point the entry's `renderer` at
     `IGNORE_FILE_RENDERER`, give it an `ignoreLabel` and set `globSyntax` to `true`. All three used
     to be separate case labels, and missing either of the last two produced an ignore file that was
     created, opted into and left holding a header with no globs under it, or a header reading "AI
     Platform": nothing thrown, nothing logged, an existence check green. `PlatformDescriptorsTest`
     now fails on a labelled entry that takes no globs and on a glob entry with no label. Assert the
     glob in the test, never the file.
   - *Delegating with a change* (a wrapper that adds or edits something): hold a shared instance
     and forward to its `render()` (`JunieRenderer`, `ClaudeSkillRenderer`). A delegate that
     changes nothing is a registry fall-through label, not a class.
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

4. **`vibetags/.../internal/content/PlatformDescriptors.java`, again** — declare
   `private static final XRenderer X_RENDERER = new XRenderer();` beside the other singletons and
   name it in your entry. Several entries may name one renderer (`CODEX`, `CODEX_CONFIG` and
   `CODEX_RULES` all name `CODEX_RENDERER`); granular platforms name the existing
   `GRANULAR_RENDERER`. `PlatformRendererRegistry` reads the table and has no switch to edit.

   A renderer must never read `PlatformDescriptors` from a static initializer: building the table
   constructs every renderer, so it would see the table half-built. Reading it from a method, which
   is what `IgnoreFileRenderer` and `AIIgnoreFormatter` do, is always safe.

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
     shape — YAML `globs:`/`description:`/`alwaysApply:` (Cursor/Trae/Continue/PearAI; Cursor and
     Trae read `globs:` as a bare comma-separated string, not a list, #699),
     YAML `trigger: glob` + `globs:` (Devin Desktop and Windsurf), YAML `paths:` (Claude), a single-string `applyTo:` (Copilot), or no front-matter at all
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

   A third pinned set decides something rather than listing it: **`ServiceRoutingContractTest.ROUTED`**.
   It fails for every new key until you classify it: does a test round's non-safety guardrails
   leave this file for `TESTING.md` when that file is present
   (`ServiceRegistry.routesTestGuardrails`)? Yes for an instruction file an agent loads as prose
   (one rendered file, with markers, not YAML); no for an ignore file, a JSON/TOML/YAML tool
   configuration, a `*_safety` file or a granular directory. When in doubt answer no: a wrongly
   routed file loses guardrails, a wrongly unrouted one only costs context. Decide what the file
   is for, then update `ROUTED` or the rule, never just the list.

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
      Setup block, and a row to `references/output-files.md` in the same skill

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

## Two traps when regenerating an example

**Clear `.vibetags-cache` first.** The write cache and fingerprint short-circuit mean an unchanged
"no diff" is ambiguous: the processor may simply not have run. `rm -f .vibetags-cache` before
rebuilding, or a stale fixture looks like a correct one.

**Regenerate `examples/multimodule` with `mvn clean verify`, not `mvn clean compile`.** Its `tests`
module has no main sources, so `compile` never shows the processor that module's annotated test
sources and it contributes nothing. CI runs `verify`, sees the module, and the byte-for-byte drift
gate goes red on a `VIBETAGS-MODULE: tests` block the local build never produced. The tell that the
fixture is stale rather than the renderer wrong: every other aggregate in that reactor already
carries a `tests` block. The Gradle reactor has the same shape — use `./gradlew clean build -x test`,
not `compileJava`.

Both reactors also assert a **hardcoded active-service count** in `.github/workflows/build.yml`
(`expected=` appears twice, once per reactor). Adding an opt-in to either example moves it, and no
Maven build checks either one. Rebuild the reactor and read the number out of its own
`vibetags.log` rather than doing the arithmetic.

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
