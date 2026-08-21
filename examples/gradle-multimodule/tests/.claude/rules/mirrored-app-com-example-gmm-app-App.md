---
paths: ["**/App.java", "**/tests/src/test/java/**/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for App

## Context & Focus
- **Focus**: Wiring only: parsing and rendering live in their own modules
- **Avoid**: Business logic

## Security Audit Requirements
When modifying this element, audit for:
- Path Traversal
<!-- VIBETAGS-END -->
