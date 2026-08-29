<!-- VIBETAGS-START -->
# Amazon Q Rules for PublicPaymentController

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Reason**: Consumed by three external partner integrations pinned to v1; signature or return-shape changes are a breaking release and need a /v2 endpoint instead
<!-- VIBETAGS-END -->
