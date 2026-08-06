<!-- VIBETAGS-START -->
# Rules for BuildFingerprint

### Rules for method fingerprint
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: O(N) in string length; uses String.hashCode() which HotSpot intrinsifies on x86; must not allocate intermediate byte[]

### Rules for method compute
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Same inputs must always produce the same 8-hex output across JVM restarts; changing the algorithm silently invalidates all existing .vibetags-cache files

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Purely stateless; private constructor prevents instantiation; all computation results are returned as values
<!-- VIBETAGS-END -->
