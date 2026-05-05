---
description: "AI rules for com.example.utils.StringParser"
globs: ["**/StringParser.java"]
alwaysApply: false
---

<!-- VIBETAGS-START -->
# Rules for StringParser

## Context & Focus
- **Focus**: Optimize for memory usage over CPU speed. Minimize object allocations and avoid creating intermediate string objects.
- **Avoid**: java.util.regex, String.split(), StringBuilder in loops
<!-- VIBETAGS-END -->
