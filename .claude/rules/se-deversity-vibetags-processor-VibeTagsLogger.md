---
paths: ["**/VibeTagsLogger.java"]
---

<!-- VIBETAGS-START -->
# Rules for VibeTagsLogger

## Thread-Safety Guarantee
- **Strategy**: THREAD_LOCAL
- **Note**: Per-thread project-root tracking partitions Logback loggers by root, so parallel compilations never detach each other's appenders (VibeTagsLoggerAsyncTest proves it)
<!-- VIBETAGS-END -->
