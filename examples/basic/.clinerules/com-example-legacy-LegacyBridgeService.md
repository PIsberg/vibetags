---
paths: ["**/LegacyBridgeService.java"]
---

<!-- VIBETAGS-START -->
# Rules for LegacyBridgeService

## Legacy Compatibility Bridge
- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.
- **Reason**: Mirrors a quirk in the upstream mainframe wire format (KEY=…;VAL=… with no escaping); 'modernizing' it broke the EBCDIC gateway in 2023
<!-- VIBETAGS-END -->
