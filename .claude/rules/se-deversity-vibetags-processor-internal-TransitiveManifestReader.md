---
paths: ["**/TransitiveManifestReader.java"]
---

<!-- VIBETAGS-START -->
# Rules for TransitiveManifestReader

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Trust boundary. Manifests read here are authored by third-party dependency JARs, and their rules are merged into the consumer's always-loaded instruction files, so a dependency can put text in front of the consumer's agent. Treat every value as untrusted input: keep the MAX_LOOKUPS cap and the SKIPPED_PREFIXES list, and route interpolation through Escape rather than widening what a manifest may contain.
<!-- VIBETAGS-END -->
