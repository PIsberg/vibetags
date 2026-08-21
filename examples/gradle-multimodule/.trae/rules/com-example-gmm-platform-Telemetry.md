---
alwaysApply: false
globs: ["**/Telemetry.java"]
description: "AI rules for com.example.gmm.platform.Telemetry"
---

<!-- VIBETAGS-START -->
# Rules for Telemetry

## Security Audit Requirements
When modifying this element, audit for:
- PII in metric labels

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: reactor.render.count, reactor.render.duration. Note: Metric names are a published contract; renaming one breaks every dashboard reading it
<!-- VIBETAGS-END -->
