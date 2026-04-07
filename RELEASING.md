# Release Guide for VibeTags

This document describes how to publish a new version of VibeTags to Maven Central and GitHub Packages.

## Prerequisites

### One-time setup

1. **Sonatype OSSRH Account**
   - You must have an account on [Sonatype's JIRA](https://issues.sonatype.org/) with permissions to publish under the `se.deversity` namespace.
   - If not yet registered, create a ticket at [Sonatype JIRA - Community Support](https://issues.sonatype.org/projects/OSSRH/issues/) to request namespace provisioning.
   - Once approved, you will receive credentials for `s01.oss.sonatype.org`.

2. **GPG Key**
   - Generate a GPG key pair (if you don't have one):
     ```bash
     gpg --full-generate-key
     # Use: RSA 4096, no expiry, real name + email matching your Sonatype account
     ```
   - Distribute your public key to a keyserver:
     ```bash
     gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
     ```
   - Export the private key for CI use:
     ```bash
     gpg --export-secret-keys --armor <YOUR_KEY_ID> > private-key.asc
     ```

3. **GitHub Repository Secrets**
   Configure the following secrets in your repository (`Settings > Secrets and variables > Actions`):

   | Secret | Description |
   |---|---|
   | `OSSRH_USERNAME` | Sonatype OSSRH username (from your JIRA account) |
   | `OSSRH_PASSWORD` | Sonatype OSSRH password (or token generated from your account) |
   | `GPG_PRIVATE_KEY` | Contents of `private-key.asc` (the ASCII-armored private key) |
   | `GPG_PASSPHRASE` | Passphrase for your GPG key |

4. **Maven `settings.xml` (local development only)**
   For manual releases from your machine, add servers to `~/.m2/settings.xml`:
   ```xml
   <servers>
     <server>
       <id>ossrh</id>
       <username>YOUR_SONATYPE_USERNAME</username>
       <password>YOUR_SONATYPE_PASSWORD</password>
     </server>
     <server>
       <id>github</id>
       <username>YOUR_GITHUB_USERNAME</username>
       <password>YOUR_GITHUB_TOKEN</password>
     </server>
   </servers>
   ```

## Release Process

### 1. Prepare the release

Create a new branch from `main` (or your default branch):

```bash
git checkout main
git pull
git checkout -b release/vX.Y.Z
```

### 2. Update the version

Update the version in all build files:

| File | Field |
|---|---|
| `vibetags/pom.xml` | `<version>` |
| `vibetags/build.gradle` | `version` |
| `vibetags/build.gradle` (publication) | `version` in `mavenJava` |
| `example/pom.xml` | `<vibetags.version>` |
| `example/build.gradle` | dependency version |
| `README.md` | dependency snippet version |

**Version rules:**
- Use a release version (e.g., `1.0.0`, not `1.0.0-SNAPSHOT`) for Maven Central.
- Use semantic versioning: `MAJOR.MINOR.PATCH`.

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

### 7. Automatic Publishing

Creating a release triggers the [Publish workflow](.github/workflows/publish.yml), which runs two parallel jobs:

1. **Publish to GitHub Packages** — deploys the artifact to `maven.pkg.github.com/PIsberg/vibetags`.
2. **Publish to Maven Central** — signs the artifacts with GPG and deploys to Sonatype OSSRH via the `central-publishing-maven-plugin`. The plugin auto-closes and publishes the staging repository.

Monitor the workflow run under the **Actions** tab. Both jobs must succeed.

### 8. Verify the Release

After the workflow completes:

- **Maven Central**: Search for `se.deversity.vibetags` at [central.sonatype.com](https://central.sonatype.com/search). It may take 15-30 minutes to appear in search and index.
- **GitHub Packages**: Check [GitHub Packages](https://github.com/PIsberg/vibetags/packages).
- **Maven Central badge**: The badge in `README.md` should update within a few hours.

### 9. Post-release

1. **Bump to next snapshot**: Create a PR that updates the version to the next `-SNAPSHOT` (e.g., `1.1.0-SNAPSHOT`).
2. **Announce**: Share the release on relevant channels.

## Snapshot Releases

For SNAPSHOT versions (e.g., `1.1.0-SNAPSHOT`):

1. Update the version in all build files.
2. Push to `main` — the build workflow will run.
3. For manual deployment:
   ```bash
   cd vibetags
   mvn clean deploy -B -DskipTests
   ```
   This deploys to the Sonatype snapshots repository (no GPG signing needed).

## Troubleshooting

### GPG signing fails in CI
- Verify `GPG_PRIVATE_KEY` secret contains the full armored private key.
- Ensure `GPG_PASSPHRASE` matches the key's passphrase.
- Check that the key was not expired or revoked.

### Sonatype deployment fails with 401
- Verify `OSSRH_USERNAME` and `OSSRH_PASSWORD` are correct.
- Ensure your account has permissions for the `se.deversity` namespace.
- Check that the `server-id` in `settings.xml` matches `ossrh` (configured in the workflow).

### Staging repository not closing
- The `central-publishing-maven-plugin` with `autoPublish=true` handles this automatically.
- If it hangs, check the Sonatype [Repository Manager](https://s01.oss.sonatype.org/) for staging repository state.
- You can manually drop/retry from the Sonatype web UI.

### Maven Central badge not updating
- The badge updates once Sonatype syncs to Maven Central (typically 15-30 min, up to 2 hours).
- Verify the artifact is accessible at `https://central.sonatype.com/artifact/se.deversity.vibetags/vibetags-processor`.

## File Checklist

Files that need version updates for each release:

```
vibetags/pom.xml              # <version>
vibetags/build.gradle         # version (top-level + publication)
example/pom.xml               # <vibetags.version>
example/build.gradle          # dependency version
README.md                     # dependency snippet
CHANGELOG.md                  # new version entry
```

## Workflow Reference

| Workflow | Trigger | What it does |
|---|---|---|
| `build.yml` | Push to `main`/`feature/*`, PRs | Multi-JDK build, tests, coverage, load tests |
| `publish.yml` | Release created | Deploys to GitHub Packages + Maven Central |
| `codeql.yml` | Push to `main`, PRs, weekly | Security scanning |
| `scorecards.yml` | Push to `main` | Supply chain security assessment |
| `dependency-review.yml` | PRs | Blocks PRs with known vulnerable deps |
