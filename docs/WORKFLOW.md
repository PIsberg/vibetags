# GitHub Actions Workflows

This document describes what happens during CI builds in `.github/workflows/`. Five workflows run on push, pull request, schedule, or release.

## Overview

| Workflow | File | Trigger |
|---|---|---|
| Build and Test | `build.yml` | Push to `main`/`master`/`feature/*`/`fix/*`, PRs to `main`/`master` |
| CodeQL | `codeql.yml` | Push/PR to `main`, weekly cron (Mondays 00:00 UTC) |
| Dependency Review | `dependency-review.yml` | Pull requests |
| Scorecard | `scorecards.yml` | Push to `main`, branch-protection-rule, weekly cron (Tuesdays 07:20 UTC) |
| Publish to Maven Central | `publish.yml` | GitHub Release `created` |

All jobs run on `ubuntu-latest` and start with the StepSecurity `harden-runner` action in `audit` mode, which records every outbound network call. The default token permission for every workflow is `contents: read`; jobs that need more (e.g. CodeQL writes `security-events`) escalate explicitly.

All third-party actions are pinned by full commit SHA with the version as a trailing comment — required by the OpenSSF Scorecard `Pinned-Dependencies` check.

---

## 1. Build and Test (`build.yml`)

The main CI workflow. Runs three jobs in parallel except `load-tests`, which waits on `build-maven`.

### Job: `build-maven`

Matrix over **JDK 17, 21, 25, 26** (Temurin distribution, Maven dependency cache). Steps:

1. **Harden runner** — egress audit.
2. **Checkout**.
3. **Set up JDK** — installs Temurin and primes the `~/.m2/repository` cache keyed on `pom.xml`.
4. **Build VibeTags Library** — `cd vibetags && mvn clean install -B`. Compiles the annotation processor, runs unit tests, and installs the artifact into the local Maven repo so the example project can resolve it.
5. **Install VibeTags BOM** — `cd vibetags-bom && mvn install -B`. Installs `se.deversity.vibetags:vibetags-bom` (pom-only) into the local Maven repo. Required because `example/pom.xml` imports the BOM via `<dependencyManagement>` to resolve the `vibetags-processor` version, and the BOM has to be resolvable before step 7 runs.
6. **Reset AI Config Files** — `cd example && bash reset-ai-files.sh`. Truncates every generated AI config file in `example/` to zero bytes and removes all granular rule files under `.cursor/rules/`, `.trae/rules/`, `.roo/rules/`. The files themselves are kept (their existence is the opt-in signal for the processor), but their content is cleared so the next compile must regenerate everything from scratch.
7. **Build Example Project** — `cd example && mvn clean compile -B -Dvibetags.log.path=../vibetags.log`. This is the only step that triggers `AIGuardrailProcessor` — it runs during `javac` of the example, sees the existing (now-empty) AI config files, and writes generated content back into them. The processor log is redirected to the repo root.
8. **Run Unit Tests (VibeTags Library)** — `cd vibetags && mvn test -B`. The `mvn install` in step 4 already ran them; this re-runs them on their own to surface failures without the install ceremony.
9. **Run Integration Tests** — `cd vibetags && mvn test -Drun.integration.tests=true -B`. These tests assume the example project has been compiled in step 7 and inspect its generated output.
10. **Verify Generated AI Config Files** — checks that 17 specific files under `example/` exist and are non-empty. Failure means the processor either skipped a platform or wrote nothing. Covered files include `.cursorrules`, `CLAUDE.md`, `.aiexclude`, `AGENTS.md`, `QWEN.md`, `gemini_instructions.md`, `.github/copilot-instructions.md`, `llms.txt`, `llms-full.txt`, `.codex/config.toml`, `.codex/rules/vibetags.rules`, `CONVENTIONS.md`, `.aiderignore`, and granular rule files for `PaymentProcessor` / `DatabaseConnector` under `.cursor/rules/`, `.trae/rules/`, `.roo/rules/`.
11. **Verify @AIAudit Content** — greps each generated file for the platform-specific phrasing of the audit section (e.g. `MANDATORY SECURITY AUDITS` in `.cursorrules`, `audit_requirements` in `CLAUDE.md`, `CONTINUOUS AUDIT REQUIREMENTS` in `gemini_instructions.md`). This catches a class of regression where the file is non-empty but the `@AIAudit` rendering has silently broken for one platform.
12. **Upload coverage to Codecov** — only on the JDK 17 matrix leg, to avoid duplicate uploads. Reads `vibetags/target/site/jacoco/jacoco.xml`. Fails CI if the upload fails.

### Job: `load-tests`

Single JDK 21 leg, `needs: build-maven`. Steps:

1. **Harden runner**, **checkout**, **set up JDK 21** (Maven cache).
2. **Install VibeTags processor** — `cd vibetags && mvn install -DskipTests -B`.
3. **Run stress tests** — `cd load-tests && mvn test -B -Dtest="AnnotationVolumeStressTest,ConcurrentBuildTest" -Dstress.max.classes=500`. Two scenarios: scaling annotation volume up to 500 classes, and concurrent builds.
4. **Upload stress-test results** — `if: always()`, so artifacts upload even on failure. Glob `load-tests/target/stress-results-*.txt`, retained as `stress-results-${{ github.run_id }}`.

### Job: `build-gradle`

Mirror of `build-maven` but with Gradle. Matrix over **JDK 17, 21, 25, 26**. Differences:

- Uses Gradle dependency cache.
- `gradle wrapper || echo "Wrapper generation skipped"` runs before each build to generate a wrapper if missing — the `||` swallows the error if a wrapper already exists.
- Library build: `gradle clean build publishToMavenLocal --no-daemon`.
- BOM install: `cd vibetags-bom && mvn install -B`. The BOM is Maven-only; Gradle reads it from `mavenLocal()` when resolving `platform('se.deversity.vibetags:vibetags-bom:...')` in `example/build.gradle`. This step runs between the library build and the example build.
- Example build: `gradle clean build -PcompilerArgs="-Avibetags.log.path=../vibetags.log" --no-daemon`.
- Tests use `gradle test --no-daemon` and `gradle test -Drun.integration.tests=true --no-daemon`.
- Codecov reads `vibetags/build/reports/jacoco/test/jacocoTestReport.xml` and uploads under flag `unittests-gradle`.

The same generated-file and `@AIAudit` verification steps run after the Gradle build, so any divergence between Maven and Gradle output paths is caught.

---

## 2. CodeQL (`codeql.yml`)

Static-analysis security scanning over Java sources.

- Triggers: push/PR to `main`, weekly cron `0 0 * * 1` (Mondays midnight UTC).
- Job permissions: `actions: read`, `contents: read`, `security-events: write`.
- Steps: harden runner → checkout → `codeql-action/init` (language `java`) → set up JDK 21 → `cd vibetags && mvn clean install -B -q` → `cd vibetags-bom && mvn install -B -q` (the example imports the BOM, so it must be resolvable before the next step) → `cd example && mvn clean compile -B -q` (so CodeQL traces both library and consumer code) → `codeql-action/analyze` with category `/language:java`.
- Findings appear under the repository's Security → Code scanning tab.

---

## 3. Dependency Review (`dependency-review.yml`)

Runs on every pull request. Steps: harden runner → checkout → `actions/dependency-review-action`. Compares the PR's dependency manifests against the base branch and surfaces any newly introduced vulnerable versions. If configured as a required check, blocks the merge.

---

## 4. Scorecard (`scorecards.yml`)

OpenSSF Scorecard supply-chain analysis.

- Triggers: push to `main`, `branch_protection_rule`, weekly cron `20 7 * * 2` (Tuesdays 07:20 UTC).
- Permissions: `read-all` workflow default; the analysis job adds `security-events: write`, `id-token: write`, plus read scopes for issues/PRs/checks needed for scorecard's GraphQL probes.
- Steps: harden runner → checkout (`persist-credentials: false` so the token doesn't leak to subsequent steps) → `ossf/scorecard-action` writing `results.sarif` → upload SARIF as a 5-day-retention artifact → `codeql-action/upload-sarif` to publish into GitHub's code-scanning dashboard.
- `publish_results: true` lets the project carry the OpenSSF Scorecard badge.

---

## 5. Publish to Maven Central (`publish.yml`)

Triggered when a GitHub Release is created.

- Job: `publish-maven-central`, JDK 21.
- Sets up Maven with `server-id: central` and exports `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` for the deploy step.
- **Import GPG key** — pipes `secrets.GPG_PRIVATE_KEY` into `gpg --batch --import`, then prints key fingerprints.
- **Build, sign, and deploy processor** — `cd vibetags && mvn clean deploy -P central-publish,sign-artifacts -B -DskipTests -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`. Tests are skipped here because they ran on every push that led to the tagged commit; this step only signs and uploads.
- **Sign and deploy BOM** — `cd vibetags-bom && mvn clean deploy -P central-publish,sign-artifacts -B -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`. Same Sonatype Central + GPG profiles as the processor pom; publishes `se.deversity.vibetags:vibetags-bom:<version>` (pom-only) so consumers can import it. Runs after the processor deploy in the same job.

Required repository secrets: `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`. CI also references `CODECOV_TOKEN` from `build.yml`.

---

## How the build verifies the annotation processor

The non-obvious part of `build.yml` is that the example project is the test fixture for the processor:

1. The library's own unit tests are pure JUnit and don't run `javac` — they assert classes, methods, and parsing logic in isolation.
2. The integration tests (gated on `-Drun.integration.tests=true`) read the files generated under `example/` after step 7.
3. `reset-ai-files.sh` is what makes step 7 a meaningful test: without it, the verification steps would pass even if the processor wrote nothing, because the files would still hold content from a previous run.
4. The `@AIAudit` grep step exists because "file is non-empty" is too weak — a partially broken processor can still emit headers and frontmatter.

Both Maven and Gradle paths run the same verification, so any platform-specific output difference (e.g. a Gradle-only file path bug) is caught.
