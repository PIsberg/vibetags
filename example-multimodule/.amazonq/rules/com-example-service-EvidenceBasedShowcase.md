<!-- VIBETAGS-START -->
# Amazon Q Rules for EvidenceBasedShowcase

## Generated — Edit The Source
- **Rule**: Machine-generated. Read it, never write it — hand edits are silently overwritten.
- **Generated from**: src/main/resources/openapi/checkout.yaml
- **Edit instead**: src/main/resources/openapi/checkout.yaml
- **Regenerate with**: mvn generate-sources

### Rules for field settledOrderIds
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: Settled orders stay in the list until the reconciliation job drains it
- **Breaks if changed**: Clearing eagerly drops in-flight settlements and silently under-reports revenue
- **Audit**: Not a defect — do not flag.

### Rules for method totalWithTax
- **Rule**: The following compile here but are prohibited at this element.
- **Forbidden**: java.lang.System.out, java.util.Date, java.lang.Double
- **Use instead**: the injected org.slf4j.Logger, java.time.Instant, and java.math.BigDecimal
- **Reason**: Console output bypasses structured logging; Date and Double are unsafe for money and time

### Rules for method refreshCartBadge
- **Rule**: Safe on exactly one thread. This is NOT thread-safety — never add locks to "fix" it; marshal the call instead.
- **Affinity**: NAMED (checkout-ui)
- **Marshal via**: CheckoutDispatcher.runOnUiThread
- **Symptom if violated**: Cart totals render stale under load; no exception is thrown

### Rules for field CATALOG_VERSION
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: pom.xml:<version>, README.md version badge, docs/CHANGELOG.md
- **Reason**: The release version is duplicated across build config, docs, and the badge
- **Enforced by**: ProjectFactsConsistencyTest
<!-- VIBETAGS-END -->
