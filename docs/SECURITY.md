# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

Only the latest release within the current major version receives security updates.

## Threat Model

VibeTags is a **compile-time** annotation processor with `RetentionPolicy.SOURCE` — it adds **zero
runtime code** to the consuming application, so there is no runtime attack surface. The processor
runs inside the consumer's `javac` during compilation and writes generated guardrail files to the
project. The trust boundary is therefore **whoever controls the source annotations and the build
configuration** — there is no remote/network input.

Two considerations are specific to this kind of tool and worth understanding:

- **Generated files are AI instructions.** By design, text from annotation attributes (`reason`,
  `note`, `focus`, `avoids`, `instructions`, …) is written into files (`CLAUDE.md`, `.cursorrules`,
  `llms.txt`, …) that AI coding agents read **as instructions**. A hostile annotation can therefore
  attempt to inject directives into an agent's context (a prompt-injection vector). This requires
  the attacker to already control source code (a malicious pull request, a compromised dependency
  that ships annotations, or an insider). **Mitigation:** treat annotation text as code in review —
  the generated files are committed, so any injected text appears in the pull-request diff. Review
  annotation strings the same way you review code from untrusted contributors.

- **Structured output is escaped, not trusted.** Annotation values interpolated into the structured
  formats are escaped per format so a value containing `<`, `"`, `\`, or newlines cannot break out
  of the document structure or forge additional entries: **XML** (`CLAUDE.md`), **JSON**
  (`.mentatconfig.json`, `.vibetags-locks`), and **double-quoted YAML** (`sweep.yaml`,
  `.plandex.yaml`, `ellipsis.yaml`). The literal-block-scalar configs (`.coderabbit.yaml`,
  `.roomodes`, the interpreter profile) embed text as indented literal blocks where there is no
  structure to break, and the JSON config files that *do* exist statically (`.qwen/settings.json`,
  `.cody/config.json`) interpolate no annotation values. Output file paths are fixed relative paths,
  and per-class file names are sanitised to `[A-Za-z0-9-]`, so a hostile class name cannot cause
  path traversal. (Regression-tested by `OutputEscapingSecurityTest`.)

## Reporting a Vulnerability

**Do not open a public issue** for security vulnerabilities.

1. Email **isberg.peter@gmail.com** with:
   - A description of the vulnerability
   - Steps to reproduce
   - Impact assessment (if possible)
   - Suggested fix (optional)

2. You will receive an acknowledgment within **48 hours**.

3. We will investigate and respond with our assessment and planned remediation within **7 days**.

4. Once the vulnerability is fixed, we will publish a security advisory and CVE (if applicable) and credit the reporter.

## Disclosure Policy

- We follow **responsible disclosure**: details are kept private until a fix is available.
- After patching, we publish a **GitHub Security Advisory** and update the CHANGELOG.
- Reporters who wish to be credited will be acknowledged in the advisory.

## Security Measures

- **Code scanning**: CodeQL runs on every push to `main` and on pull requests.
- **Dependency review**: PRs introducing vulnerable dependencies are blocked.
- **Dependabot**: Automated dependency updates for Maven, GitHub Actions, and npm.
- **OpenSSF Scorecard**: Supply-chain security assessment runs weekly.
- **Secret scanning**: Pre-commit hooks (gitleaks) prevent secrets from being committed.
- **GPG-signed releases**: All releases are signed; signatures are verified by Maven Central.

## Scope

This policy covers the `vibetags-processor` library and its annotation processor. The `example/` and `load-tests/` subprojects are for demonstration and benchmarking purposes only and are not intended for production use.
