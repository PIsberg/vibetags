<!-- VIBETAGS-START -->
# AI Guidelines for ParallelTestSettings

## Strict Test Isolation
- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.
- **Reason**: Tests here bind to fixed port 8080; a shared static counter caused flaky CI in build #4471 — keep cases isolated
<!-- VIBETAGS-END -->
