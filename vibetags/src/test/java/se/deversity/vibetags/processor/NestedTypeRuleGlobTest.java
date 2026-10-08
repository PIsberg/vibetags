package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nested type lives in its outermost type's source file, so that is the file its scoped rule
 * must load for. The glob was built from the nested type's own simple name, {@code **}{@code /Entry.java}
 * for {@code Ledger.Entry}: a file that does not exist, so the rule never loaded when the code it
 * guards was opened, or an unrelated {@code Entry.java} elsewhere, which loaded it for the wrong
 * code. Members of a nested type file under it, and inherited the same dead glob.
 */
@Tag("e2e")
class NestedTypeRuleGlobTest {

    private static final String RULE = ".claude/rules/com-example-billing-Ledger-Entry.md";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aNestedTypesRuleLoadsForItsOutermostTypesFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.billing.Ledger",
            "package com.example.billing;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "public class Ledger {\n"
                + "    @AIContext(focus = \"Entry layout is the wire format\")\n"
                + "    public static class Entry {\n"
                + "        @AIContext(focus = \"Rounding is half-even\")\n"
                + "        public long amount() { return 0; }\n"
                + "    }\n"
                + "}\n");

        h.compile();

        String rule = h.readFile(RULE);
        assertTrue(rule.startsWith("---\npaths: [\"**/Ledger.java\"]\n---\n"),
            "Ledger.Entry's code is in Ledger.java, so that is what the rule must load for. Was:\n" + rule);
        assertTrue(rule.contains("Entry layout is the wire format") && rule.contains("Rounding is half-even"),
            "the type's and its member's guardrails share the rule. Was:\n" + rule);
    }

    /** Two levels deep: still the outermost type's file, not the middle one's. */
    @Test
    void aDoublyNestedTypesRuleLoadsForTheOutermostFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.billing.Ledger",
            "package com.example.billing;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "public class Ledger {\n"
                + "    public static class Entry {\n"
                + "        @AIContext(focus = \"Codes are ISO 4217\")\n"
                + "        public enum Currency { SEK }\n"
                + "    }\n"
                + "}\n");

        h.compile();

        String rule = h.readFile(".claude/rules/com-example-billing-Ledger-Entry-Currency.md");
        assertTrue(rule.startsWith("---\npaths: [\"**/Ledger.java\"]\n---\n"), "Was:\n" + rule);
    }

    /**
     * Invariant 12: the glob is generated content, so what it is built from reaches the fingerprint.
     * {@code com.example.billing.Ledger.Entry} is the same path and kind whether {@code Entry} is
     * nested in a class {@code Ledger} or top-level in a package of that name; only the file differs.
     * Left out of the fingerprint, the second build matched the first and kept its glob.
     */
    @Test
    void movingANestedTypeToItsOwnFileRegeneratesTheGlob(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.billing.Ledger",
            "package com.example.billing;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "public class Ledger {\n"
                + "    @AIContext(focus = \"Entry layout is the wire format\")\n"
                + "    public static class Entry {}\n"
                + "}\n");
        h.compile();
        assertTrue(h.readFile(RULE).startsWith("---\npaths: [\"**/Ledger.java\"]\n---\n"), h.readFile(RULE));

        h.clearSources();
        h.addSource("com.example.billing.Ledger.Entry",
            "package com.example.billing.Ledger;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"Entry layout is the wire format\")\n"
                + "public class Entry {}\n");
        h.compile();

        String rule = h.readFile(RULE);
        assertTrue(rule.startsWith("---\npaths: [\"**/Entry.java\"]\n---\n"),
            "Entry is in Entry.java now. Was:\n" + rule);
    }

    /** A top-level type keeps the glob it always had. */
    @Test
    void aTopLevelTypesRuleIsUnchanged(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.billing.Ledger",
            "package com.example.billing;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"Append only\")\n"
                + "public class Ledger {}\n");

        h.compile();

        String rule = h.readFile(".claude/rules/com-example-billing-Ledger.md");
        assertTrue(rule.startsWith("---\npaths: [\"**/Ledger.java\"]\n---\n"), "Was:\n" + rule);
    }
}
