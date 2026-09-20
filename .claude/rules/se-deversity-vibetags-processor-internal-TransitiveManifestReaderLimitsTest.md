---
paths: ["**/TransitiveManifestReaderLimitsTest.java"]
---

<!-- VIBETAGS-START -->
# Rules for TransitiveManifestReaderLimitsTest

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Enforces the trust boundary: manifests come from third-party dependency JARs and their text is merged into the consumer's always-loaded instruction files. These cases are the MAX_LOOKUPS cap and the SKIPPED_PREFIXES list; relaxing one to make a test pass widens what a dependency may put in front of an agent
<!-- VIBETAGS-END -->
