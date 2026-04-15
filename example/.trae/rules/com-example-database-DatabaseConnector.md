---
alwaysApply: false
globs: ["**/DatabaseConnector.java"]
description: "AI rules for com.example.database.DatabaseConnector"
---

<!-- VIBETAGS-START -->
# Rules for DatabaseConnector

## Security Audit Requirements
When modifying this element, audit for:
- SQL Injection
- Thread Safety issues

### Rules for field username
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Database credential - never log or include in error messages

### Rules for field password
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Database credential - never log or include in error messages
<!-- VIBETAGS-END -->
