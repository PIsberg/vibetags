<!-- VIBETAGS-START -->
# Rules for VibeTagsLogger

## Thread-Safety Guarantee
- **Strategy**: THREAD_LOCAL
- **Note**: Logback loggers are partitioned by root and by compiling thread, so parallel compilations, two modules sharing one root included, never detach each other's appenders (VibeTagsLoggerAsyncTest and VibeTagsLoggerConcurrencyTest prove it)
<!-- VIBETAGS-END -->
