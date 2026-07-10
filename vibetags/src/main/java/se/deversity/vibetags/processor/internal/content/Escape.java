package se.deversity.vibetags.processor.internal.content;

/**
 * Escapes interpolated values for the structured output formats VibeTags generates, so a value
 * containing format metacharacters (from a method signature with generics, or from a hostile
 * annotation attribute) cannot break out of the document structure or forge additional entries.
 *
 * <p>Markdown / plain-text platforms (e.g. {@code .cursorrules}) intentionally do <em>not</em> use
 * these — their content is free text and there is no structure to break.
 */
public final class Escape {

    private Escape() {}

    /**
     * Escapes a value for inclusion in XML text content or a double-quoted attribute (used by the
     * {@code CLAUDE.md} XML format). Escapes {@code & < > " '}.
     */
    public static String xml(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes a value for a JSON string literal (the caller supplies the surrounding quotes). Used
     * by the JSON outputs ({@code .mentatconfig.json}, {@code .qwen/settings.json},
     * {@code .cody/config.json}). Escapes {@code " \}, the standard control shorthands, and any
     * other control character as {@code \\uXXXX}.
     */
    public static String json(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Escapes a value for a TOML multi-line basic string (the caller supplies the surrounding
     * {@code """} delimiters). Used by {@code .pr_agent.toml}. Escapes {@code \} and {@code "} —
     * every quote, not just runs of three, so no {@code """} can survive to close the string
     * early. Newlines and tabs are left literal (the format permits them and the body is
     * multi-line prose); every other control character becomes {@code \\uXXXX}, which a raw
     * TOML basic string forbids.
     */
    public static String tomlMultiline(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append('\n'); break;
                case '\t': sb.append('\t'); break;
                case '\r': sb.append("\\r"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
