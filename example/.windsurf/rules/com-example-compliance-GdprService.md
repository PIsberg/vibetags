---
description: "AI rules for com.example.compliance.GdprService"
globs: ["**/GdprService.java"]
alwaysApply: false
---

<!-- VIBETAGS-START -->
# Rules for GdprService

## Regulatory Compliance
- **Standard**: GDPR
- **Clause**: Art. 17
- **Description**: Right to erasure — when invoked, deletes ALL PII for the given user across every connected store.

### Rules for method exportUserData
- **Standard**: GDPR
- **Clause**: Art. 20
- **Description**: Right to data portability — exports the user's data in a machine-readable format.
<!-- VIBETAGS-END -->
