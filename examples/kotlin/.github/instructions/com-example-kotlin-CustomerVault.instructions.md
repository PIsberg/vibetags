---
applyTo: "**/CustomerVault.kt"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for CustomerVault

## PII / Privacy Guardrails
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Holds customer PII — never log, expose, or include field values in suggestions.

### Rules for method findCustomer
- **Sensitivity**: critical
- **Note**: Primary lookup path for every checkout; covered by the vault contract suite
<!-- VIBETAGS-END -->
