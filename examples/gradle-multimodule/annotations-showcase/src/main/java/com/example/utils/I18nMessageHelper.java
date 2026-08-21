package com.example.utils;

import se.deversity.vibetags.annotations.AIInternationalized;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Demo message helper class annotated with {@link AIInternationalized}.
 * Prohibits hardcoding of user-facing strings. All user-visible text must be
 * resolved via resources.
 */
@AIInternationalized(reason = "Ships in 11 locales; a hardcoded English string here shipped to the German build last quarter and failed the l10n audit — always resolve via the bundle")
public class I18nMessageHelper {

    private final ResourceBundle messages;

    public I18nMessageHelper(Locale locale) {
        // AI must not hardcode messages, and instead load them from resource bundle
        this.messages = ResourceBundle.getBundle("messages", locale);
    }

    public String getMessage(String key) {
        return messages.getString(key);
    }
}
