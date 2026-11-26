package se.deversity.vibetags.processor.internal;

import java.nio.charset.StandardCharsets;

/**
 * Finds and replaces the one kind of Java string UTF-8 cannot encode: a UTF-16 surrogate without
 * its partner.
 *
 * <p>Annotation attributes are Java strings, so a mistyped unicode escape puts a lone surrogate
 * into one, and a dependency's transitive manifest can deliver one too ({@code Json} accepts the
 * escape, as the JSON grammar does). A strict encoder then throws before writing a byte, which
 * dropped every rule of the file with the one bad character (#1141). {@link #replaced} gives each
 * such character the {@code ?} that {@link String#getBytes} already gives it in the module
 * sidecar, so a rule reads the same whichever of the two a file was built from.
 */
public final class UnpairedSurrogates {

    private UnpairedSurrogates() {
    }

    /** Index of the first unpaired surrogate in {@code s}, or {@code -1} when every one is paired. */
    public static int firstIn(CharSequence s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i += 2;
            } else if (Character.isSurrogate(c)) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * {@code s} with every unpaired surrogate replaced by {@code ?}, or {@code s} itself when it
     * has none. The replacement is the UTF-8 encoder's own, so it matches what a sidecar holds.
     */
    public static String replaced(String s) {
        if (firstIn(s) < 0) {
            return s;
        }
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
