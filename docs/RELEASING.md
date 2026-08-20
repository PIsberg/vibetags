# Release Guide for VibeTags

This document describes how to publish a new version of VibeTags to Maven Central (via the Sonatype Central Portal) and GitHub Packages.

## Prerequisites

### One-time setup

#### 1. Central Portal Account

1. Go to [https://central.sonatype.com](https://central.sonatype.com) and click **Sign In**.
2. Register using Google, GitHub, or a custom username/password.
3. Verify your email address (required for publishing).

> **Note:** The legacy OSSRH/JIRA system (`issues.sonatype.org`) has been replaced by the Central Portal. If you previously had an OSSRH account, you still need to register on the new Portal.

#### 2. Claim Your Namespace (groupId)

After registering, you must claim the namespace under which you will publish artifacts. The namespace is your `groupId` (e.g., `se.deversity`).

**Supported groupId patterns:**

| Pattern type | Examples | Notes |
|---|---|---|
| **Domain-based (reversed)** | `com.example`, `org.springframework`, `com.my-domain`, `sub.example.com` | Must prove domain ownership |
| **Code hosting-based** | `io.github.username`, `io.gitlab.username`, `io.bitbucket.username` | Personal usernames auto-granted; orgs need verification |

**Registration steps:**

1. Log in to [central.sonatype.com](https://central.sonatype.com).
2. Click your username (top-right) → **View Namespaces** → **Add Namespace**.
3. Enter your chosen namespace (e.g., `se.deversity`) and click **Submit**. Status will be `Unverified`.
4. Choose a verification method:

   **Option A: DNS verification** (for domain-based namespaces like `se.deversity` → `deversity.se`):
   - Go to your DNS registrar/hosting provider (e.g., AWS Route 53, Cloudflare, GoDaddy).
   - Add a **TXT record** to the **root domain** (e.g., `deversity.se`, *not* `se.deversity.com`).
     - **Name/Host:** `@` (or bare domain)
     - **Value:** Copy the **Verification Key** from the Portal (use the clipboard icon)
   - Wait for DNS propagation (can take minutes to hours).
   - Back in the Portal, click **Verify Namespace** → **Confirm**.
   - Refresh the dashboard to check for `Verified` status.

   > **Warning:** Do not click "Verify" until the TXT record has fully propagated. Premature verification causes `NXDOMAIN` caching that delays retries.

   **Option B: Code hosting verification** (for `io.github.username` style):
   - Create a new **public** repository on GitHub/GitLab/Gitee/Bitbucket.
   - **Repository name** must exactly match the Portal-assigned **Verification Key**.
   - Expected path: `github.com/<username>/<verification-key>`.
   - In the Portal, click **Verify Namespace** → **Confirm**.
   - The repository can be deleted after verification succeeds.

5. Once verified, the namespace status changes to `Verified` and publishing is immediately enabled.

> For VibeTags, the namespace is `se.deversity`. This requires DNS verification on the `deversity.se` domain.

#### 3. Generate a Portal User Token

The Central Portal uses **User Tokens** (not your web login credentials) for programmatic access:

1. Log in to [https://central.sonatype.com](https://central.sonatype.com).
2. Click your account name in the top-right corner.
3. Select **View User Tokens**.
4. Click **Generate User Token**.
5. The portal displays a **token username** and **token password**.
6. **Save them immediately** — the password is shown only once and cannot be retrieved later.

These tokens replace the old OSSRH JIRA username/password.

#### 4. GPG Key Setup

GPG signing is **mandatory** for Maven Central publishing.

**Generate a GPG key:**
```bash
gpg --full-generate-key
# Algorithm: RSA (4096-bit)
# Expiry: Your choice (or 0 for no expiry)
# Real name and email: Must match your Central Portal account
# Passphrase: Use a strong, unique passphrase
```

**Verify the key:**
```bash
gpg --list-keys --keyid-format LONG
# Note the key ID (8-character hex string, e.g., ABCD1234)
```

**Distribute your public key** to a supported keyserver (SKS network is deprecated):
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
# Or: keys.openpgp.org, pgp.mit.edu
```

**Export the private key for CI use:**
```bash
gpg --export-secret-keys --armor <YOUR_KEY_ID> > vibetags-private-key.asc
```

> **Important:** Maven/Nexus only verify signatures against the **primary key**. If your keyring has a subkey with signing capability (`usage: S`), remove or revoke it:
> ```bash
> gpg --edit-key <YOUR_KEY_ID>
> gpg> revkey
> gpg> save
> ```

#### 5. GitHub Repository Secrets

Configure the following secrets in your repository (`Settings > Secrets and variables > Actions`):

| Secret | Description |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | Central Portal User Token username (from step 3) |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal User Token password (from step 3) |
| `GPG_PRIVATE_KEY` | Contents of `vibetags-private-key.asc` (the full ASCII-armored private key) |
| `GPG_PASSPHRASE` | Passphrase for your GPG key |

> **Legacy secrets no longer needed:** `OSSRH_USERNAME` and `OSSRH_PASSWORD` are deprecated. The Central Portal User Token replaces them.

#### 6. Maven `settings.xml` (local development only)

For manual releases from your machine, add servers to `~/.m2/settings.xml`:
```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_CENTRAL_PORTAL_TOKEN_USERNAME</username>
      <password>YOUR_CENTRAL_PORTAL_TOKEN_PASSWORD</password>
    </server>
    <server>
      <id>gpg.passphrase</id>
      <passphrase>YOUR_GPG_PASSPHRASE</passphrase>
    </server>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

## Release Process

### 0. Check the consumers still build

The suite in `vibetags/` proves the processor works. It does not prove a real project still
compiles against it. Before cutting anything, build every downstream consumer against what is
about to be released:

```bash
# only if main is ahead of the newest tag, which it usually is
cd vibetags-annotations && mvn install -DskipTests
cd ../vibetags         && mvn install -DskipTests
cd ../vibetags-bom     && mvn install

bash scripts/consumer-sweep.sh <version>
```

The `consumer-regression-suite` skill drives this and covers how to read the results — in
particular, that a failure is not a regression until it has been shown to pass on the
consumer's existing pinned version.

While the tree is being prepared, `scripts/bump-dependencies.sh` reports which third-party
pins in `vibetags-parent/pom.xml` (and the Gradle wrapper, Kotlin, Groovy and Scala pins in the
examples) have a newer stable release. Applying them is its own PR, before the release branch,
driven by the `bump-dependencies` skill; a release should not be the first build on a new
plugin version.

### 1. Prepare the release

Create a new branch from `main` (or your default branch):

```bash
git checkout main
git pull
git checkout -b release/vX.Y.Z
```

### 2. Update the version

Update the version:

```bash
scripts/set-version.sh 1.0.0
cd vibetags && mvn test -Dtest=BuildVersionParityTest
```

That is the whole bump. The version lives in **one** place — `<revision>` in
`vibetags-parent/pom.xml` — and every pom that inherits from the parent takes its own version,
its sibling dependencies and its BOM entries from it. `vibetags-annotations/pom.xml`,
`vibetags/pom.xml`, `vibetags-bom/pom.xml` and `load-tests/pom.xml` are not edited at all.

The script also rewrites the places that *cannot* inherit a Maven property:

| File | Why it needs rewriting |
|---|---|
| `vibetags-annotations/build.gradle`, `vibetags/build.gradle` | Gradle cannot inherit from a Maven POM, and both publish under this version |
| `vibetags-annotations/pom.xml`, `vibetags-bom/pom.xml` | Prose only — the `<description>` shows consumers a copy-pasteable snippet containing a literal version |
| `example/pom.xml`, `example/build.gradle`, `example-kotlin/build.gradle.kts`, `example-multimodule/pom.xml`, `example-multimodule-indexed/pom.xml`, `tools/demo/pom.xml` | Standalone on purpose so a user can lift them into their own project; CI builds them against the artifacts this repo just installed, so they track the current version |

Still by hand, because they are prose rather than build files:

| File | Field |
|---|---|
| `README.md` | dependency snippets and BOM snippet versions |
| `.claude/skills/vibetags-usage/SKILL.md` | version in the install snippets |
| `docs/vibetags-in-practice.md` | version references, if any |

> **`BuildVersionParityTest` is the check, not this table.** It fails the build when any Gradle
> file, example pom or managed pom disagrees with `<revision>`, so a forgotten file is a red test
> rather than a wrong artifact. It was written after `load-tests/pom.xml` spent two releases pinned
> to `0.9.5` while CI believed its regression gates were testing the branch.

> `load-tests/pom.xml` keeps its own `<processor.version>`, defaulting to `${revision}`. The pin is
> deliberate — comparing releases means pointing the harness at an older processor — and CI
> overrides it with the version the run just built, so the gates test the branch regardless.

> The processor's own version needs no manual bump. `AIGuardrailProcessor.VERSION` is
> `ProcessorVersion.get()`, which reads `Implementation-Version` from the jar manifest (falling
> back to Maven's embedded `pom.properties`, then to `"dev"` when running from `target/classes`).
> Bumping `<revision>` is what moves it.

> Tip: `grep -rn "<old-version>" --include="*.xml" --include="*.gradle" --include="*.md" --include="*.java" --exclude-dir=.git --exclude-dir=target --exclude-dir=build --exclude-dir=results --exclude-dir=node_modules --exclude-dir=changelog-assets .` catches every spot. Expected residue after a bump: the previous version's CHANGELOG entry, frozen `load-tests/results/<old>/` baselines, and `load-tests/dependency-reduced-pom.xml` (auto-regenerated by maven-shade on next build). The `.git` exclusion skips `PR_BODY.md` / `RELEASE_NOTES.md` scratch files left by earlier releases, which are full of stale versions and must not be edited.

**Version rules:**
- Use a release version (e.g., `1.0.0`, not `1.0.0-SNAPSHOT`) for Maven Central.
- Use semantic versioning: `MAJOR.MINOR.PATCH`.
- An observable behaviour change is MAJOR-worthy even when the old behaviour was a bug, if
  working consumers or correct persisted data (caches, sidecars, lock reports) exist under the
  old behaviour. A version number is a compatibility promise, not a verdict on whether the old
  code deserved to work. Ask "does correct data exist under the old behaviour?" before shipping
  such a fix as a patch.

### 3. Update the CHANGELOG

Edit `CHANGELOG.md`:
- Rename the `[Unreleased]` section to `[X.Y.Z] - YYYY-MM-DD`.
- Add a new empty `[Unreleased]` section at the top.
- Update the comparison links at the bottom.

### 4. Commit and push

```bash
git add -A
git commit -m "chore: prepare release vX.Y.Z"
git push origin release/vX.Y.Z
```

### 5. Create a Pull Request

Open a PR from `release/vX.Y.Z` to `main`. Ensure:
- [ ] CI passes (build, tests, CodeQL, Scorecards).
- [ ] The version is correct.
- [ ] `CHANGELOG.md` is updated.

Merge the PR into `main`.

### 6. Create a GitHub Release

Go to [GitHub Releases](https://github.com/PIsberg/vibetags/releases) and click **Create a new release**:

1. **Tag version**: `vX.Y.Z` (e.g., `v0.5.0`)
2. **Target**: `main`
3. **Title**: `VibeTags vX.Y.Z`
4. **Description**: Copy the relevant section from `CHANGELOG.md` (or let GitHub auto-generate from the release.yml template).
5. Check **Set as latest release** if applicable.
6. Click **Publish release**.

#### Image-path gotcha — must rewrite relative paths

`docs/CHANGELOG.md` uses paths like `changelog-assets/0.X.Y/foo.png` for embedded images. **In the repo view** GitHub resolves these relative to the file's location (`docs/`), so they work. **In a GitHub Release page** GitHub resolves the same paths from the **repo root**, so they 404.

Before uploading the release notes, rewrite the relative paths to absolute raw-content URLs pinned to the tag. From the repo root:

```bash
TAG=v0.7.1
awk -v v="[${TAG#v}]" '
  index($0, "## " v) == 1 { f=1; print; next }
  f && /^## \[/ { exit }
  f { print }
' docs/CHANGELOG.md \
  | sed "s|](changelog-assets/${TAG#v}/|](https://github.com/PIsberg/vibetags/raw/${TAG}/docs/changelog-assets/${TAG#v}/|g" \
  > /tmp/release-notes-${TAG}.md

gh release create $TAG \
  --target main \
  --title "VibeTags $TAG" \
  --notes-file /tmp/release-notes-${TAG}.md \
  --latest
```

> **Do not use an `awk` range here.** `awk '/^## \[1.2.3\]/,/^## \[/'` returns the header
> line and nothing else: when a range's start and end patterns both match the same record, awk
> closes the range on that record, so the `exit` guard inside it never runs. That form shipped in
> this document and produced a one-line release note for 1.2.3. The flag-driven version above
> starts on the header and stops at the *next* `## [`.

If you forget the `sed`, the release will publish with broken image links. Fix afterward via:

```bash
gh release view $TAG --json body --jq .body \
  | sed "s|](changelog-assets/${TAG#v}/|](https://github.com/PIsberg/vibetags/raw/${TAG}/docs/changelog-assets/${TAG#v}/|g" \
  > /tmp/r.md
gh release edit $TAG --notes-file /tmp/r.md
```

### 7. Automatic Publishing

Creating a release triggers the [Publish workflow](../.github/workflows/publish.yml), which runs two parallel jobs:

1. **Publish to GitHub Packages** — deploys the artifact to `maven.pkg.github.com/PIsberg/vibetags` using the `-P github` profile.
2. **Publish to Maven Central** — signs the artifacts with GPG (`-P sign-artifacts`) and deploys all three artifacts (`vibetags-annotations`, `vibetags-processor`, `vibetags-bom`) to the Central Portal via the `central-publishing-maven-plugin`. The plugin auto-publishes without manual approval (`autoPublish=true`). Order matters: annotations must be deployed before the processor (which depends on them).

Monitor the workflow run under the **Actions** tab. Both jobs must succeed.

### 8. Verify the Release

After the workflow completes:

- **Maven Central**: Search for `se.deversity.vibetags` at [central.sonatype.com](https://central.sonatype.com/search). It may take 15-30 minutes to appear in search and sync to mirrors.
- **GitHub Packages**: Check [GitHub Packages](https://github.com/PIsberg/vibetags/packages).
- **Maven Central badge**: The badge in `README.md` should update within a few hours.
- **Deployments dashboard**: Monitor at [https://central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).

### 9. Post-release

1. **Bump to next snapshot**: Create a PR that updates the version to the next `-SNAPSHOT` (e.g., `0.6.0-SNAPSHOT`).
2. **Announce**: Share the release on relevant channels.

## Snapshot Releases

For SNAPSHOT versions (e.g., `0.6.0-SNAPSHOT`):

1. Update the version in all build files.
2. Ensure your namespace has **snapshots enabled** in the Central Portal (toggle in namespace settings).
3. Push to `main` — the build workflow will run.
4. For manual deployment:
   ```bash
   cd vibetags
   mvn clean deploy -B -DskipTests
   ```
   This deploys to the Central Portal snapshots repository (no GPG signing needed — the `sign-artifacts` profile is not activated).

> **Note:** The `central-publishing-maven-plugin` handles both release and snapshot deployments. Snapshots are published to `https://central.sonatype.com/repository/maven-snapshots/`.

## Troubleshooting

### GPG signing fails in CI
- Verify `GPG_PRIVATE_KEY` secret contains the **full** ASCII-armored private key (including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`).
- Ensure `GPG_PASSPHRASE` matches the key's passphrase exactly.
- Check that the key was not expired or revoked.
- Confirm the primary key (not a subkey) has signing capability.

### Central Portal deployment fails with 401
- Verify `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` are correct (they are **not** your web login credentials).
- Regenerate the User Token from the Central Portal UI if needed.
- Ensure your account has namespace ownership for `se.deversity`.

### Deployment stuck or fails validation
- Check the [Deployments dashboard](https://central.sonatype.com/publishing/deployments) for the specific validation error.
- Common issues: missing Javadoc JAR, missing sources JAR, missing GPG signatures, POM metadata mismatch.
- The `central-publishing-maven-plugin` with `autoPublish=true` handles close/release automatically. If validation fails, fix the issue and redeploy.

### Maven Central badge not updating
- The badge updates once Sonatype syncs to Maven Central mirrors (typically 15-30 min, up to 2 hours).
- Verify the artifact is accessible at `https://central.sonatype.com/artifact/se.deversity.vibetags/vibetags-processor`.

### Local `mvn deploy` tries to publish to Central unintentionally
- The `central-publishing-maven-plugin` is always active. To skip Central deployment during local development, use the `github` profile:
  ```bash
  mvn deploy -P github -DskipTests
  ```
- To build and install locally without any remote deployment:
  ```bash
  mvn install -DskipTests
  ```

### "No GPG key found" during local signing
- Ensure you have a GPG key installed: `gpg --list-keys`
- If you need to specify a specific key, add to `~/.m2/settings.xml`:
  ```xml
  <server>
    <id>gpg.passphrase</id>
    <passphrase>YOUR_PASSPHRASE</passphrase>
  </server>
  ```
- Or pass the key ID directly: `mvn deploy -P sign-artifacts -Dgpg.keyname=YOUR_KEY_ID`

## Architecture: How Publishing Works

### Maven build (`pom.xml`)

```
vibetags/pom.xml
├── <build><plugins>
│   ├── maven-source-plugin       → Generates -sources.jar (required by Central)
│   ├── maven-javadoc-plugin      → Generates -javadoc.jar (required by Central)
│   └── central-publishing-maven-plugin → Uploads to Central Portal
├── <distributionManagement>
│   ├── <snapshotRepository id="central"> → https://central.sonatype.com/repository/maven-snapshots/
│   └── <repository id="central">        → Central Portal staging
└── <profiles>
    ├── sign-artifacts     → Activates maven-gpg-plugin (signs all artifacts)
    └── github             → Overrides distributionManagement to GitHub Packages
```

### CI workflow (`.github/workflows/publish.yml`)

```
Release created on GitHub
├── publish-github-packages
│   ├── server-id: github
│   ├── activates profile: github
│   └── deploys to: maven.pkg.github.com/PIsberg/vibetags
└── publish-maven-central
    ├── server-id: central
    ├── activates profile: sign-artifacts
    ├── imports GPG key from secrets
    ├── signs all artifacts (jar, sources, javadoc, pom)
    └── deploys to: central.sonatype.com (auto-published)
```

### Key differences from the legacy OSSRH process

| Old OSSRH | New Central Portal |
|---|---|
| JIRA ticket for namespace registration | Self-service namespace in Portal UI |
| `ossrh` server ID | `central` server ID |
| `nexus-staging-maven-plugin` | `central-publishing-maven-plugin` |
| JIRA username/password for auth | Portal User Token (username + password) |
| Manual "Close" → "Release" in Nexus UI | `autoPublish=true` or manual approval in Portal Deployments UI |
| `s01.oss.sonatype.org` URLs | `central.sonatype.com` URLs |
| SKS keyservers for GPG distribution | `keyserver.ubuntu.com`, `keys.openpgp.org`, `pgp.mit.edu` |

## File Checklist

See the table in [§2 Update the version](#2-update-the-version) for the authoritative list of
files needing a version bump, and run `scripts/set-version.sh` to cover the three core modules
in one pass. This section used to keep a second copy of that list; it drifted out of date, so
the table is now the only place it lives.

## Workflow Reference

| Workflow | Trigger | What it does |
|---|---|---|
| `build.yml` | Push to `main`/`feature/*`, PRs | Multi-JDK build, tests, coverage, load tests |
| `publish.yml` | Release created | Deploys to GitHub Packages + Maven Central (parallel) |
| `codeql.yml` | Push to `main`, PRs, weekly | Security scanning |
| `scorecards.yml` | Push to `main` | Supply chain security assessment |
| `dependency-review.yml` | PRs | Blocks PRs with known vulnerable deps |

## Useful Links

- [Sonatype Central Portal](https://central.sonatype.com)
- [Central Portal Documentation](https://central.sonatype.org/publish/)
- [Deployments Dashboard](https://central.sonatype.com/publishing/deployments)
- [Maven Central Search](https://central.sonatype.com/search)
- [GPG Requirements](https://central.sonatype.org/publish/requirements/gpg/)
