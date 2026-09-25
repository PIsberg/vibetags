<!-- VIBETAGS-START -->
# Rules for TransitiveManifestReaderLimitsTest

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Pins the MAX_LOOKUPS cap and the SKIPPED_PREFIXES list of TransitiveManifestReader; relaxing a case to make it pass widens what a dependency may put in front of an agent
<!-- VIBETAGS-END -->
