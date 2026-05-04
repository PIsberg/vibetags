# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.5.x   | :white_check_mark: |
| < 0.5   | :x:                |

Only the latest minor release within the current major version receives security updates.

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
