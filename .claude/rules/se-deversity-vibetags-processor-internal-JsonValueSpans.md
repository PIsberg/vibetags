---
paths: ["**/JsonValueSpans.java"]
---

<!-- VIBETAGS-START -->
# Rules for JsonValueSpans

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Splices annotation text, including attributes copied out of third-party dependency JARs, into greptile.json, a review configuration the user owns. The span body must stay Escape.json-encoded and marker-defused: without the first a dependency can close the string and add settings such as skipReview, and without the second it can end the span early so the value grows a copy of itself on every build.

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: Edits are offsets spliced into the original text; the document is validated with Json but never parsed and re-serialised.
- **Breaks if changed**: Re-serialising rewrites key order, whitespace, number spelling and the user's own escape sequences in a file VibeTags does not own, on every build, with nothing failing except the byte-preservation cases in JsonValueSpansTest and GreptileEndToEndTest.
<!-- VIBETAGS-END -->
