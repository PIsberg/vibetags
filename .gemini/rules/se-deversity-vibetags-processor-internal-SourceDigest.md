<!-- VIBETAGS-START -->
# Rules for SourceDigest

## Context & Focus
- **Focus**: Every input that shapes generated output and is not an -A option, an opt-in file or a .vibetags-* config must be hashed here
- **Avoid**: Dropping or missing an input: a no-op rebuild then keeps stale output, and a test that compiles once never reaches the early exit, so nothing fails
<!-- VIBETAGS-END -->
