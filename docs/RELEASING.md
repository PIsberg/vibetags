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

bash tools/consumer-sweep.sh <version>
```

The `consumer-regression-suite` skill drives this and covers how to read the results — in
particular, that a failure is not a regression until it has been shown to pass on the
consumer's existing pinned version.

While the tree is being prepared, `tools/bump-dependencies.sh` reports which third-party
pins in `vibetags-parent/pom.xml` (and the Gradle wrapper, Kotlin, Groovy and Scala pins in the
examples) have a newer stable release. Applying them is its own PR, before the release branch,
driven by the `bump-dependencies` skill; a release should not be the first build on a new
plugin version.

### 0b. Re-check the platform list against the vendors

Every platform VibeTags writes names a file that some vendor documents, or did when it was added.
Vendors rename paths, retire products and get acquired, and nothing in the build can notice: the
file keeps being generated for a tool that no longer reads it. In the two platform sweeps so far,
every path that was checked against the vendor's own docs instead of a cross-tool round-up turned
out wrong or stale on at least one count (see [PLATFORMS.md](PLATFORMS.md)).

List every path from the platform table itself, so the list cannot drift from what the code
writes:

```bash
python corpus/check-platforms.py list vibetags/src/main/java/se/deversity/vibetags/processor/internal/content/PlatformDescriptors.java
```

For each tool, open the vendor's own documentation and confirm two things: the product still
exists, and its docs still name that path. Where the docs are behind a login or render only in a
browser, look for the file in real repositories on GitHub instead. Then act on what you find:

- **Still documented.** Nothing to do.
- **Renamed or superseded.** Open an issue to write the current path.
- **Retired, or no longer documented.** Deprecate it, do not remove it: it needs a compiler
  warning on every opted-in build for at least one release, then removal in the next major
  version. 1.4.0 removed the last deprecated output and the `DeprecatedServices` table that raised
  that warning (#720); restore the table from git history, add the row and a PLATFORMS.md entry,
  and open the removal issue. Removing it in a minor release leaves consumers with a file that silently stops
  regenerating.
- **Could not check.** Say so in the release PR, by name. An unchecked row is not a confirmed one.

### 0c. Count this month's releases

Maven Central allows an organization 7 releases, 1,167 files and 78 MB per calendar month.
Going over starts a grace period, and an organization that stays over is rate limited
([publishing limits](https://central.sonatype.org/publish/maven-central-publishing-limits/),
#863). September 2026 used all 7 releases. So:

- **At most 3 releases a month.** The exception is a fix a consumer is blocked on, and the
  release PR says so.
- Count before tagging:
  ```bash
  git for-each-ref --format='%(creatordate:format:%Y-%m) %(refname:short)' 'refs/tags/v*' \
    | grep "^$(date +%Y-%m) "
  ```
- **84 files per release.** Each of the four jar artifacts publishes 5 files (jar, pom,
  sources, javadoc, `-cyclonedx.json`) and each file goes up with its `.asc`, `.md5` and
  `.sha1`; the BOM adds its pom with the same three. Until #863 it was 150: the publishing
  plugin's default also sent `.sha256` and `.sha512`, which Central accepts but does not
  require ([requirements](https://central.sonatype.org/publish/requirements/)), and the SBOM
  went up as XML too. `<checksums>required</checksums>` in the `central-publish` profile and
  `<outputFormat>json</outputFormat>` in each module's CycloneDX plugin are what keep it at 84;
  `CentralPublishingBudgetTest` pins both.
- Actual usage is on the [usage page](https://central.sonatype.com/publishing/usage?org=deversity)
  (owner login). Whether Central counts each of `publish.yml`'s five deployments as a release
  is not known yet; the page says.

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
tools/set-version.sh 1.0.0
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
| `examples/basic/pom.xml`, `examples/basic/build.gradle`, `examples/kotlin/build.gradle.kts`, `examples/multimodule/pom.xml`, `examples/multimodule-indexed/pom.xml`, `tools/demo/pom.xml` | Standalone on purpose so a user can lift them into their own project; CI builds them against the artifacts this repo just installed, so they track the current version |

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

`docs/CHANGELOG.md` uses relative paths for embedded images, both `changelog-assets/0.X.Y/foo.png` and `../load-tests/results/_plots/foo.png`. **In the repo view** GitHub resolves these relative to the file's location (`docs/`), so they work. **In a GitHub Release page** GitHub resolves the same paths from the **repo root**, so they 404.

`tools/release-notes.sh` resolves every relative link against `docs/` and pins it to the tag: raw-content URLs for images, `blob/` URLs for other files. Absolute links and `#anchors` pass through. A link that resolves outside the repository makes it refuse and emit nothing. Until #849 it rewrote only `changelog-assets/`, and a release's `../load-tests/` plots had to be fixed by hand. From the repo root:

```bash
TAG=v<version>
tools/release-notes.sh "${TAG#v}" > /tmp/release-notes-${TAG}.md

gh release create $TAG \
  --target main \
  --title "VibeTags $TAG" \
  --notes-file /tmp/release-notes-${TAG}.md \
  --latest
```

> **Do not inline the extraction, in any form.** `awk '/^## \[1.2.3\]/,/^## \[/'` returns the
> header line and nothing else: when a range's start and end patterns both match the same
> record, awk closes the range on that record, so the `exit` guard inside it never runs. That
> form shipped in this document and produced a one-line release note for 1.2.3. It was
> corrected here, with this warning, and the release skill kept its own broken copy and
> produced the same one-line extract again at 1.3.3 (#619). Two copies of a subtle command is
> the defect, so `tools/release-notes.sh` is now the only implementation: it starts on the
> header, stops at the *next* `## [`, and refuses to emit fewer than five lines so a
> truncated file cannot reach a step that cannot be undone.

If you forget the `sed`, the release will publish with broken image links. Fix afterward via:

```bash
gh release view $TAG --json body --jq .body \
  | sed "s|](changelog-assets/${TAG#v}/|](https://github.com/PIsberg/vibetags/raw/${TAG}/docs/changelog-assets/${TAG#v}/|g" \
  > /tmp/r.md
gh release edit $TAG --notes-file /tmp/r.md
```

### 7. Automatic Publishing

Creating a release triggers the [Publish workflow](../.github/workflows/publish.yml). Its one job,
**Publish to Maven Central**, signs the artifacts with GPG (`-P sign-artifacts`) and deploys
`vibetags-annotations`, `vibetags-processor`, `vibetags-ksp`, `vibetags-bom` and `vibetags-cli`, in
that order, to the Central Portal via the `central-publishing-maven-plugin`. The plugin
auto-publishes without manual approval (`autoPublish=true`). Order matters: annotations must be
deployed before the processor (which depends on them). GitHub Packages publishing was removed; only
Central is published to.

Every deploy runs on the Maven pinned in the job's `env` (`MAVEN_VERSION`, `MAVEN_SHA512`), not the
runner image's: Maven 3.10 makes the plugin bundle files Central rejects (#945). Central sees a
bundle only after the tag exists, so the `Central Bundle Shape` check in `build.yml` builds every
module's bundle with that Maven on each pull request. It must be green on the release PR.

Monitor the workflow run under the **Actions** tab; the job must succeed. If it fails, see
[A release published nothing, or only some modules](#a-release-published-nothing-or-only-some-modules).

### 8. Verify the Release

After the workflow completes:

- **Maven Central**: Search for `se.deversity.vibetags` at [central.sonatype.com](https://central.sonatype.com/search). It may take 15-30 minutes to appear in search and sync to mirrors.
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

### A release published nothing, or only some modules

A failed validation publishes nothing, so the version is still free on Central: finish the release
from the same tag. Never delete and re-cut the tag, and do not release the next version to get
around it.

1. Read the failing step's log. Central's validation errors follow `Deployment <id> failed`.
   `Bundle has content that does NOT have a .pom file: <group>/<artifact>` means the bundle held a
   file outside its `<group>/<artifact>/<version>/` directory. That was the 2026-10-08 failure:
   the runner image's Maven had become 3.10, and the plugin zipped its `maven-metadata-local.xml`
   (#945). Counting `Pre Bundling - deleted` lines in the log shows which Maven built the bundles:
   the plugin logs one per module after cleaning up Maven 3.9's staging metadata, and none under
   Maven 3.10.
2. Fix the cause on `main` through a pull request. The `Central Bundle Shape` check builds every
   module's bundle and must pass on it.
3. Resume from the tag. The dispatch runs `publish.yml` as it is on `main` against the tag's source,
   so the fix applies although the tag predates it:
   ```bash
   gh workflow run publish.yml --ref main -f ref=v<version> -f modules=all
   ```
   `deploy-to-central.sh` treats a module that already landed as success, so `modules=all` is safe
   after a partial publish. With `modules=all`, and `ref` the tag of the latest release, the run also
   attaches the signed artifacts to the release; for any other release, upload them with
   `gh release upload`.
4. Check every module on `repo1.maven.org`, not just the run's status:
   ```bash
   for a in annotations processor ksp bom cli; do
     curl -s -o /dev/null -w "vibetags-$a: %{http_code}\n" \
       "https://repo1.maven.org/maven2/se/deversity/vibetags/vibetags-$a/<version>/vibetags-$a-<version>.pom"
   done
   ```

### Maven Central badge not updating
- The badge updates once Sonatype syncs to Maven Central mirrors (typically 15-30 min, up to 2 hours).
- Verify the artifact is accessible at `https://central.sonatype.com/artifact/se.deversity.vibetags/vibetags-processor`.

### Local `mvn deploy` tries to publish to Central unintentionally
- The `central-publishing-maven-plugin` runs only under `-P central-publish`, which `publish.yml`
  passes. Without it, `mvn deploy` falls back to `maven-deploy-plugin` and the
  `<distributionManagement>` in `vibetags-parent/pom.xml`: a `-SNAPSHOT` goes to Central's snapshot
  repository when `~/.m2/settings.xml` has credentials for `central`, and a release version goes to
  the OSSRH staging URL, which Sonatype has retired (it answered 404 when checked on 2026-10-08).
  The `github` profile this entry used to recommend no longer exists.
- To see the bundle a release would upload without uploading it, run
  `bash .github/scripts/build-central-bundles.sh` from the repository root, as the
  `Central Bundle Shape` check does.
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
vibetags-parent/pom.xml, inherited by every published module
├── <build><plugins>
│   └── flatten-maven-plugin      → Resolves ${revision}, drops the parent from the deployed POM
│       (each jar module binds maven-source-plugin and maven-javadoc-plugin itself:
│        -sources.jar and -javadoc.jar, both required by Central)
├── <distributionManagement>
│   ├── <snapshotRepository id="central"> → https://central.sonatype.com/repository/maven-snapshots/
│   └── <repository id="central">        → retired OSSRH staging URL; releases use the profile below
└── <profiles>
    ├── central-publish    → central-publishing-maven-plugin: bundles the module, uploads it to the Central Portal
    └── sign-artifacts     → Activates maven-gpg-plugin (signs all artifacts)
```

### CI workflow (`.github/workflows/publish.yml`)

```
Release created on GitHub, or a manual dispatch with ref=<tag> to resume one
└── publish-maven-central
    ├── env MAVEN_VERSION, MAVEN_SHA512 → installs that Maven, refuses to deploy on any other (#945)
    ├── server-id: central
    ├── imports GPG key from secrets
    ├── per module, in order: deploy-to-central.sh → mvn clean deploy -P central-publish,sign-artifacts
    │   (annotations, processor, ksp, bom, cli; signs jar, sources, javadoc, pom)
    ├── deploys to: central.sonatype.com (auto-published)
    └── attaches the signed jars and .asc files to the GitHub release
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
files needing a version bump, and run `tools/set-version.sh` to cover the three core modules
in one pass. This section used to keep a second copy of that list; it drifted out of date, so
the table is now the only place it lives.

## Workflow Reference

| Workflow | Trigger | What it does |
|---|---|---|
| `build.yml` | Push to `main`/`master`, every PR | Multi-JDK build, tests, coverage, load tests, Central bundle shape |
| `publish.yml` | Release created; manual dispatch to resume one | Deploys the five modules to Maven Central |
| `codeql.yml` | Push to `main`, PRs, weekly | Security scanning |
| `scorecards.yml` | Push to `main` | Supply chain security assessment |
| `dependency-review.yml` | PRs | Blocks PRs with known vulnerable deps |

## Useful Links

- [Sonatype Central Portal](https://central.sonatype.com)
- [Central Portal Documentation](https://central.sonatype.org/publish/)
- [Deployments Dashboard](https://central.sonatype.com/publishing/deployments)
- [Maven Central Search](https://central.sonatype.com/search)
- [GPG Requirements](https://central.sonatype.org/publish/requirements/gpg/)
