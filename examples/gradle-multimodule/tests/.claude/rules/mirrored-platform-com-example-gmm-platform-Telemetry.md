---
paths: ["**/Telemetry.java", "**/tests/src/test/java/**/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for Telemetry

## Locked Status
- **Reason**: Metric names are a published contract; renaming one breaks every dashboard and alert reading them

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: reactor.render.count, reactor.render.duration. Note: Metric names are a published contract; renaming one breaks every dashboard reading it
<!-- VIBETAGS-END -->
