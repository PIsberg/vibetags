---
paths: ["**/ServiceRegistry.java"]
---

<!-- VIBETAGS-START -->
# Rules for ServiceRegistry

## Context & Focus
- **Focus**: Maps platform service keys to output file paths; resolves active services by checking file existence on disk
- **Avoid**: Creating output files that do not already exist — file presence on disk is the user's explicit opt-in signal
<!-- VIBETAGS-END -->
