---
paths: ["**/DatabaseConnector.java"]
---

<!-- VIBETAGS-START -->
# Rules for DatabaseConnector

## Security Audit Requirements
When modifying this element, audit for:
- SQL Injection
- Thread Safety issues

## PII / Privacy Guardrails
- **Rule**: Never log or expose runtime values of these elements.
- **Reason**: Database credential - never log or include in error messages
- **Applies to**: `DatabaseConnector.username`, `DatabaseConnector.password`
<!-- VIBETAGS-END -->
