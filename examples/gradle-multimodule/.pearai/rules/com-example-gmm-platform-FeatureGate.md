---
description: "AI rules for com.example.gmm.platform.FeatureGate"
globs: ["**/FeatureGate.java"]
alwaysApply: false
---

<!-- VIBETAGS-START -->
# Rules for FeatureGate

## Feature Flag Gate
- **Flag**: 'reactor.parallel-render' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.
<!-- VIBETAGS-END -->
