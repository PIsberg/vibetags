# Proposed Annotations — Evidence from 225 Open-Source `CLAUDE.md` Files

> **Status:** proposal, nothing implemented. Numbers below are counts of *distinct
> open-source repositories* whose hand-written `CLAUDE.md` states the rule.

## Method

Every rule in this document was reverse-engineered from guardrails that real
maintainers wrote **by hand**, which is the point: a hand-written AI rule is a
constraint someone wished they could express in code. Where a rule has no
VibeTags annotation, that is a gap in the library.

1. GitHub code search for `filename:CLAUDE.md` across several query slices →
   **327 unique repositories**.
2. Fetched each file from its default branch; kept the substantial ones
   (≥ 3 KB — one-line stubs encode no rules) → **225 files, 2.26 MB**.
3. Split into 6 disjoint batches, mined in parallel by independent agents. Each
   was given the existing 39 annotations and told to report only what they do
   **not** cover, and only rules that attach to a *code element* (project
   workflow — build commands, commit conventions — was discarded).
4. Because the batches are disjoint, per-batch repo counts sum cleanly.

The strongest signal is **agreement**: the six agents could not see each other's
work, and the top clusters surfaced in every single batch.

## Ranked candidates

| Tag | Repos | Batches |
|---|---:|---:|
| [`@AIKeepInSync`](#aikeepinsync) | 41 | 6 / 6 |
| [`@AIGenerated`](#aigenerated) | 35 | 6 / 6 |
| [`@AILoadBearing`](#ailoadbearing) | 31 | 6 / 6 |
| [`@AIBannedApi`](#aibannedapi) | 27 | 4 / 6 |
| [`@AIVersionPinned`](#aiversionpinned) | 21 | 4 / 6 |
| `@AINoHardcodedValue` | 16 | 3 / 6 |
| `@AIOrdering` | 13 | 3 / 6 |
| `@AISpecBound` | 13 | 3 / 6 |
| `@AICommentPolicy` | 13 | 2 / 6 |
| [`@AIThreadAffinity`](#aithreadaffinity) | 12 | 4 / 6 |
| `@AITestContract` | 12 | 3 / 6 |
| `@AIApprovalRequired` | 12 | 3 / 6 |
| `@AINoSilentFailure` | 10 | 2 / 6 |
| [`@AIDynamicallyReferenced`](#aidynamicallyreferenced) | 5 | 1 / 6 |

---

## The structural finding

The gaps are not scattered. **VibeTags owns the positive pole of several axes and
is missing the negative pole**, and an AI reading the *absence* of a tag reliably
does the wrong thing:

| Existing (positive) | Missing (negative) | Why the absence is dangerous |
|---|---|---|
| `@AIThreadSafe` — safe from *any* thread | `@AIThreadAffinity` — safe from *exactly one* thread | These are opposite claims. Tagging a `@MainActor`/EDT-pinned method `@AIThreadSafe` would be a **lie**; leaving it untagged invites "let's move this off the main thread." |
| `@AIExtensible` — extend via strategy/visitor | `@AIClosedSet` — this set is complete by design | An AI's helpfulness bias is to add the missing sibling. Sometimes the omission *is* the design. |
| `@AIStrictClasspath` — no reflection *inside* this element | `@AIDynamicallyReferenced` — this element is *reached by* reflection | Zero static callers ⇒ looks dead ⇒ gets deleted with high confidence. |
| `@AILocked` — a wall | `@AIGenerated`, `@AIApprovalRequired` — a **door** | `@AILocked` can only say "stop." These say "stop, **and here is the correct route**." |

That last row explains why `@AIGenerated` scored 35: maintainers kept writing
*"don't edit this — edit **that**, then run **this**"*, and `@AILocked` can only
express the first third of that sentence. A dead end makes an agent give up or
route around it; a redirect lets it still do the job.

---

## `@AIKeepInSync`

**41 repos · all 6 batches.** The most-written rule in the corpus.

This element is duplicated at N named sites. It is free to change — the failure
mode is a *partial* change that silently desyncs a mirror the compiler never
checks.

> "This mapping exists in four places and must be kept in sync: `admin/api/upload.php`,
> `admin/api/delete.php`, `dev-server.js` (`UPLOAD_TARGETS`), and the delete regex."
> — GeKay99/Blutgesang

> "**There are TWO committed reference schema dumps… any PR that adds/edits/removes a
> migration MUST regenerate BOTH in the same PR.**" — llun/activities.next

> "**Three locations must stay synchronized:** `pubspec.yaml` → `version: X.Y.Z`;
> `lib/package_info.dart`; `README.md`" — optimizely/optimizely-flutter-sdk

> "the Python formatters live in `agentmemory.marketplace` … and the TypeScript side
> mirrors them byte-for-byte in `brainctl-launch/lib/marketplace/memos.ts`" — TSchonleber/brainctl

**Not covered by:** `@AIContract` freezes *one* signature so it cannot change.
`@AISchemaSafe` is storage-specific. Neither expresses "edit A ⇒ you must also edit B."

```java
@Retention(SOURCE) @Target({TYPE, METHOD, FIELD})
public @interface AIKeepInSync {
    String[] mirrors();          // paths / FQNs that must move together
    String reason() default "";
    String enforcedBy() default ""; // parity test or CI check, if one exists
}
```

**Design note — this one points outside the compilation unit.** The mirrors are
`pubspec.yaml`, a README badge, a TypeScript twin. A Java annotation can *name*
them but the processor cannot verify them the way `@AIImmutable` verifies final
fields. The natural home is the existing `.vibetags-locks` machinery: emit a
`.vibetags-mirrors` JSON-Lines report and let the locked-files GitHub Action fail
any PR that touches one side of a mirror set without the other. That would be a
genuinely new capability, not just new generated prose.

**Related sub-cluster (8 repos, batch 03): `@AIRegistrationRequired`** — a new
implementor of an interface is *inert* until registered in a factory switch /
registry map / DI provider set. Arguably `@AIKeepInSync` hosted on the base type;
worth deciding whether it folds in or stands alone.

---

## `@AIGenerated`

**35 repos · all 6 batches.**

Machine-generated. Hand edits are silently overwritten. Names the true source and
the regeneration command.

> "Files under `generated` directories are generated based on a different source file
> and should not be modified directly. Find the underlying source and modify that
> instead." — nrwl/nx

> "Public API interfaces (e.g. `src/Playwright/API/Generated/IPage.cs`) are
> **generated** … Do not hand-edit these — update the generator instead."
> — microsoft/playwright-dotnet

> "Editing one file by hand is almost always wrong: the change will be lost on the next
> toolkit regeneration and will not propagate to the other ~hundreds of OS/PHP/nginx
> combinations." — dwchiang/nginx-php-fpm

> "**Always edit C# files** in `cs/` directory, never generated C++ directly"
> — JoeStrout/miniscript2

**Not covered by:** `@AILocked` says "don't modify" and stops — no forward path.
`@AIIgnore` excludes the element from context entirely, which is *wrong*: an agent
must still **read** generated types to understand behavior, just never write them.

```java
@Retention(SOURCE) @Target({TYPE, METHOD, FIELD})
public @interface AIGenerated {
    String from();                    // source schema / template / upstream repo
    String regenerateWith() default ""; // e.g. "mvn generate-sources"
    String editInstead() default "";  // the file a human should actually change
}
```

Note the dogfooding angle: VibeTags already writes marker-delimited generated
regions. `@AIGenerated` is the annotation for exactly that situation.

---

## `@AILoadBearing`

**31 repos · all 6 batches.**

The code *looks* wrong, redundant, or over-defensive. It is deliberate, and the
annotation records the failure that "cleaning it up" reintroduces. Uniquely, it
also covers the **intentional omission** case — a missing decorator, a skipped
dealloc — which no existing tag can express, because there is no element to
annotate for something that isn't there.

> "Retired VM sessions are kept alive (not deallocated) to prevent VZ dispatch source
> use-after-free crashes" — rderaison/bromure

> "**Overlay dismiss does NOT call `window.close()`** — only `orderOut` + clear
> `contentView`. Calling `close()` during animated dismiss causes EXC_BAD_ACCESS."
> — sorkila/lockpaw

> "individual views do **not** need per-view decorators … their absence is intentional,
> not a security gap, and must not be flagged during audits" — SACGF/variantgrid

> "**Do NOT schema-qualify PostGIS function calls** … `pg_dump` clears `search_path`
> during restore, breaking these references." — stac-utils/pgstac

> "BPM is stored as int * 100 in the database (e.g., 12800 = 128.0 BPM)."
> — davehenke/rekordbox-mcp

**Not covered by:** `@AILocked` forbids *all* edits; here edits are fine as long as
one invariant survives. `@AIExplain` demands a proof of correctness *from* the AI;
this *supplies* the rationale *to* it. `@AIContext.avoids` is soft prose with no
failure mode attached.

```java
@Retention(SOURCE) @Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface AILoadBearing {
    String invariant();                  // what must remain true
    String breaksIf() default "";        // the concrete failure: crash, leak, silent desync
    boolean suppressAudit() default false; // "not a defect — stop flagging it"
}
```

---

## `@AIBannedApi`

**27 repos · 4 batches.**

A named API is forbidden *at this element* even though it compiles, and a
designated wrapper is the only sanctioned route.

> "**NEVER use the stdlib `log` package** (`log.Printf`, etc.) in application code. Use
> the injected structured logger everywhere." — Aurealibe/claude-config

> "**Never call `fetch()` directly in React components.** All client-side API calls must
> be added to `lib/client.ts`" — llun/activities.next

> "**Scheduled tasks**: MUST use `JobConfig` table + `JobSchedulerService`. NEVER use
> `@Scheduled` annotation." — mwangli/stock-trading

> "Do **not** use `@Transactional` on service methods. It causes performance issues…"
> — ls1intum/thesis-management

**Not covered by:** `@AIDeprecated` annotates the *old* element — but `fetch`,
`log.Printf`, and `@Scheduled` are third-party or stdlib symbols you **cannot
annotate**. The constraint must therefore be hosted on the consumer (or the
wrapper) and point *outward*. `@AIArchitecture(cannotReference)` bans a *layer*,
not a *symbol*, and carries no `useInstead`.

```java
@Retention(SOURCE) @Target({TYPE, METHOD})
public @interface AIBannedApi {
    String[] forbidden();          // symbols / packages
    String useInstead() default "";
    String reason() default "";
}
```

---

## `@AIVersionPinned`

**21 repos · 4 batches.** The corpus's most common *anti-hallucination* guardrail.

The element binds to a pinned library/runtime version, and the model's
training-data recollection of that API is presumed **wrong**.

> "If a library's documented API differs from what you know — trust the pinned version,
> not training data." / "Tailwind v4 is a complete rewrite from v3… Do not follow v3
> patterns." — radekamirko/C.R.I.S.P

> "**jSerialComm is pinned to 2.10.2** — the last release before it bundled an Android
> USB-serial driver, whose `android.*` references fail the native build" — nvdweem/PCPanel

> "The project uses **Bootstrap 4**. Use `data-toggle` (not `data-bs-toggle`)"
> — SACGF/variantgrid

> "**NEVER assume library APIs haven't changed. Always verify.**" — Aurealibe/claude-config

**Not covered by:** `@AIStrictClasspath` bans reflection. `@AIDeprecated` marks
*our* symbol as dying. Nothing says "this third-party version is a hard pin — do
not emit the newer idiom you remember, and do not upgrade it for me."

```java
@Retention(SOURCE) @Target({TYPE, FIELD, METHOD})
public @interface AIVersionPinned {
    String library();
    String version();
    String[] forbiddenIdioms() default {}; // "ReactDOM.render", "data-bs-toggle"
    String breaksBecause() default "";
}
```

---

## `@AIThreadAffinity`

**12 repos · 4 batches.** The clearest "missing negative pole."

> "`KeyMonitor.start()` is asserted to run on the main queue via `dispatchPrecondition`.
> This is load-bearing" — wr/mojito

> "never touch widgets from an executor thread (segfaults under load, passes silently on
> a quiet workstation)" — Innopolis-Robotics-Society/llm_swarm

> "All Grasshopper document access must go through `_context.ExecuteOnUiThread()`"
> — brookstalley/cordyceps

**Not covered by:** `@AIThreadSafe` promises safety *under concurrent access*.
Affinity is the inverse: unsafe from every thread but one. An AI told "make this
thread-safe" will add a lock — precisely the wrong fix.

```java
@Retention(SOURCE) @Target({TYPE, METHOD})
public @interface AIThreadAffinity {
    Affinity value();                  // MAIN_ONLY, NEVER_MAIN, BACKGROUND_ONLY, NAMED
    String thread() default "";        // when NAMED — "EDT", "event-loop"
    String marshalVia() default "";    // "SwingUtilities.invokeLater"
    String symptomIfViolated() default "";
}
```

---

## `@AIDynamicallyReferenced`

**5 repos · 1 batch.** Low frequency, high value — include it for the failure mode,
not the count.

The element has **zero static callers**. It is reached by reflection, a config
string, a filename convention, or a build-time scan. It looks dead. It is not.

> "These look like frontend things but the API depends on them — never delete blindly"
> — decentraland/events

> "**One function per file**, filename must exactly match the function name (enforced by
> `Main.Tests.ps1`)." — EUCPilots/evergreen-module

> "`CommandManager.DiscoverAndCacheCommands()` finds all `BaseCommand` subclasses from
> both the main assembly and loaded plugins" — Gideon-Taylor/AppRefiner

**Not covered by:** the exact *inverse* of `@AIStrictClasspath`. This defends
against the deletion an agent is most confident about — "unused, removing it" —
which is unrecoverable in a way most bad edits aren't. Highly relevant to Java
(Spring component scan, JPA entities, `ServiceLoader`, JUnit discovery).

```java
@Retention(SOURCE) @Target({TYPE, METHOD, FIELD})
public @interface AIDynamicallyReferenced {
    Mechanism mechanism();            // REFLECTION, CONFIG_STRING, FILENAME_CONVENTION, BUILD_SCAN, SPI
    String[] referencedBy() default {};
    boolean nameIsLoadBearing() default false;
}
```

---

## Caveats

**The corpus is not Java.** It skews heavily to TypeScript, Go, Rust, Swift and
Python. Several high-frequency clusters are real findings but *not Java findings*
— hydration-safety (SSR), design tokens, and WCAG accessibility have no Java
analogue and are **excluded** from the ranked list above on purpose. The five
leaders all translate cleanly (generated sources, mirrored constants, load-bearing
oddities, banned APIs, pinned deps), which is why they are the recommended set.

**Some clusters overlap existing tags at the edges.** `@AINoSilentFailure` sits
next to `@AIStrictExceptions` (which bans catching over-broad *types* but not
*swallowing* a correctly-typed one). `@AIResponseEnvelope` is arguably an
attribute on `@AIContract`. These need a judgement call, not just a count.

**Cost per tag is real.** Landing one touches the `@interface`, a `*Formatter`
(~12 platform branches), `FormatterRegistry`, `AnnotationCollector`,
`BuildFingerprint`, `GranularRenderer`, plus definition / end-to-end / validation
tests — and `ProjectFactsConsistencyTest` fails the build until the README's
"**39 annotations**" line is updated. That argues for landing the evidence-heavy
five, not all fourteen.

## Suggested order

1. **`@AIGenerated`** — highest value-per-line. Pure redirect semantics, no new
   machinery, and VibeTags already generates marker-delimited regions itself.
2. **`@AILoadBearing`** — the "don't clean this up" tag. Covers intentional
   omissions, which nothing else can.
3. **`@AIBannedApi`** — mechanically simple; hosts the constraint on the consumer
   so it works against unannotatable stdlib/third-party symbols.
4. **`@AIThreadAffinity`** — closes a genuine correctness hole where the current
   library forces a *false* statement (`@AIThreadSafe`) or silence.
5. **`@AIKeepInSync`** — highest frequency but the largest design question, since
   enforcement wants a `.vibetags-mirrors` report + Action rather than prose.
