# VibeTags: Granular rules and transitive guardrails

Part of the `vibetags-usage` skill; [SKILL.md](../SKILL.md) holds setup and the element cheat sheet.

## Granular Rules

When the granular rule directories exist, VibeTags generates **one rule file per annotated class** instead of a single monolithic config file. Each rule file is automatically scoped to its class (e.g., `**/OrderService.java`). Orphaned files for classes that lose their annotations are cleaned up automatically.

| Directory | Platform | Format |
|---|---|---|
| `.claude/rules/*.md` | Claude Code | YAML front-matter (`paths:`) + Markdown |
| `.github/instructions/*.instructions.md` | GitHub Copilot | YAML front-matter (`applyTo:`) + Markdown |
| `.cursor/rules/*.mdc` | Cursor | YAML front-matter + Markdown |
| `.windsurf/rules/*.md` | Devin Desktop, formerly Windsurf (fallback directory) | YAML front-matter + Markdown |
| `.devin/rules/*.md` | Devin Desktop (preferred directory) | YAML front-matter (`trigger: glob`) + Markdown |
| `.trae/rules/*.md` | Trae IDE | YAML front-matter + Markdown |
| `.roo/rules/*.md`, `.rooignore` | Zoo Code (fork of the retired Roo Code; reads the same paths) | Markdown |
| `.continue/rules/*.md` | Continue | YAML front-matter + Markdown |
| `.tabnine/guidelines/*.md` | Tabnine | Markdown |
| `.amazonq/rules/*.md` | Amazon Q (deprecated) | Markdown |
| `.ai/rules/*.md` | Universal AI standard (deprecated) | Markdown |
| `.pearai/rules/*.md` | PearAI (deprecated) | YAML front-matter + Markdown |
| `.kiro/steering/*.md` | Amazon Kiro | Markdown |
| `.grok/rules/*.md` | Grok Build | Markdown |
| `.agents/rules/*.md` | Antigravity | Markdown |
| `.aiassistant/rules/*.md` | JetBrains AI Assistant | Markdown |
| `.augment/rules/*.md` | Augment Code | Markdown |

Enable by creating the directories:
```bash
mkdir -p .cursor/rules .devin/rules .trae/rules .roo/rules
mkdir -p .continue/rules .tabnine/guidelines
mkdir -p .kiro/steering .grok/rules
mkdir -p .agents/rules .aiassistant/rules .augment/rules
mkdir -p .claude/rules .github/instructions
```

---

## Transitive Guardrails — rules that arrive from a dependency

An agent working in an application reads *that* application's `CLAUDE.md`, never the one belonging
to a library it depends on. A library can publish its package-level guardrails, and any project
that opts in renders them into its own AI configuration.

Both halves are file-presence opt-ins, like everything else here. Neither file exists by default.

**Publishing (the library).** Annotate `package-info.java`, then add `.vibetags-manifest`:

```java
// src/main/java/com/acme/crypto/api/package-info.java
@AISecure(aspect = "Never construct a raw Cipher; go through CryptoManagerFactory.")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE,
    note = "Every product of the factory is safe to share between threads.")
package com.acme.crypto.api;

import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
```

```bash
# first non-comment line is the coordinate consumers will see
echo "com.acme:crypto-core:2.4.0" > .vibetags-manifest
```

The build writes `vibetags/manifests/com.acme.crypto.api.json` into the class output and the normal
`jar` task packages it. (Not `META-INF/` — javac's `CLASS_PATH` location skips archive directories
whose names are not valid package identifiers, so a manifest there is unreadable from a processor.)

**Consuming (the application).**

```bash
touch .vibetags-transitive
```

```markdown
<!-- appended to CLAUDE.md, after everything your own code declares -->
## Inherited Guardrails (dependencies)

- `com.acme.crypto.api` (from com.acme:crypto-core:2.4.0)
  - @AISecure: aspect=Never construct a raw Cipher; go through CryptoManagerFactory.

## Inherited Context (dependencies)

- `com.acme.crypto.api` (from com.acme:crypto-core:2.4.0)
  - @AIThreadSafe: strategy=IMMUTABLE; note=Every product of the factory is safe to share between threads.
```

Worth knowing:

- **The project's own rules come first, always.** The inherited block is appended last. That
  ordering *is* the precedence model — the output is prose an agent reads, not a ruleset a compiler
  applies, so a library cannot outrank the project consuming it.
- **Every inherited rule names its artifact**, because a dependency is contributing text an agent
  will act on and the reader has to see whose text it is.
- **Only packages the compilation imports are looked up**, so a hundred instrumented dependencies
  do not become a hundred pages of prompt. `-Avibetags.manifest.max=<n>` caps the advisory tier
  further; the six safety buckets are never dropped, and a cap that drops anything says so.
- **Only package-level annotations travel.** Class- and method-level guardrails stay local — a
  consumer cannot act on a rule about a class it never sees. Thirteen annotations accept
  `ElementType.PACKAGE`: `@AISecure`, `@AIPrivacy`, `@AICore`, `@AIAudit`, `@AIRegulation`,
  `@AIArchitecture`, `@AIPublicAPI`, `@AIBannedApi`, `@AIThreadSafe`, `@AIImmutable`,
  `@AIDeprecated`, `@AIContext`, `@AIStrictClasspath`.
- **kapt, ECJ and JPMS need a hand.** Discovery needs the compiler's Tree API and the classpath;
  where either is missing VibeTags reports a `NOTE` rather than pretending it found nothing, and
  the manifests are supplied with `-Avibetags.manifest.dir=<dir>` or
  `-Avibetags.manifest.packages=a.b,c.d`. Plain Gradle needs none of that.
- **Markers are resolved against `-Avibetags.root`.** A build that pins the root at a module
  directory needs `.vibetags-transitive` in that directory, not only at the reactor root.
