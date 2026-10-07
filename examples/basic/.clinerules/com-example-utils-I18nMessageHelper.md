---
paths: ["**/I18nMessageHelper.java"]
---

<!-- VIBETAGS-START -->
# Rules for I18nMessageHelper

## Internationalization Mandate
- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources.
- **Reason**: Ships in 11 locales; a hardcoded English string here shipped to the German build last quarter and failed the l10n audit — always resolve via the bundle
<!-- VIBETAGS-END -->
