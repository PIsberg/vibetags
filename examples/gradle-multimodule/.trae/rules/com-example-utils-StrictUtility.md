---
alwaysApply: false
globs: ["**/StrictUtility.java"]
description: "AI rules for com.example.utils.StrictUtility"
---

<!-- VIBETAGS-START -->
# Rules for StrictUtility

## Strict Classpath Integrity
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.
- **Reason**: Runs inside the locked-down payment sandbox where the SecurityManager forbids reflection and custom classloaders; dynamic loading throws at runtime
<!-- VIBETAGS-END -->
