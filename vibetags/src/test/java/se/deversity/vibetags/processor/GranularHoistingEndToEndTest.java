package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for issue #313: the constant guardrail sentence must appear once per section
 * in a generated granular rule file, however many elements the section covers.
 */
class GranularHoistingEndToEndTest {

    private static final String KEYS_SOURCE = """
        package com.example.crypto;
        import se.deversity.vibetags.annotations.AIPrivacy;
        public class KeyBundle {
            @AIPrivacy(reason = "raw key material")
            private byte[] secret;
            @AIPrivacy(reason = "derived session key")
            private byte[] session;
            @AIPrivacy(reason = "long-term identity key")
            private byte[] identity;
        }
        """;

    private static final String SINGLE_SOURCE = """
        package com.example.crypto;
        import se.deversity.vibetags.annotations.AIPrivacy;
        public class Solo {
            @AIPrivacy(reason = "only one")
            private byte[] secret;
        }
        """;

    private static final String ROLE_SOURCE_A = """
        package com.example.crypto;
        import se.deversity.vibetags.annotations.AIPrivacy;
        public class Alpha {
            @AIPrivacy(reason = "alpha key")
            private byte[] k;
        }
        """;

    private static final String ROLE_SOURCE_B = """
        package com.example.crypto;
        import se.deversity.vibetags.annotations.AIPrivacy;
        public class Beta {
            @AIPrivacy(reason = "beta key")
            private byte[] k;
        }
        """;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private ProcessorTestHarness granularHarness() throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".claude/rules"));
        return harness;
    }

    @Test
    void threePrivacyFieldsInOneClass_stateTheRuleOnce() throws IOException {
        ProcessorTestHarness harness = granularHarness();
        harness.addSource("com.example.crypto.KeyBundle", KEYS_SOURCE);
        harness.compile();

        String rules = Files.readString(root.resolve(".claude/rules/com-example-crypto-KeyBundle.md"));

        assertEquals(1, count(rules, "Never log or expose runtime values"),
            "the constant rule sentence must be stated once, not once per field:\n" + rules);
        assertTrue(rules.contains("of these elements."),
            "the hoisted sentence is pluralized:\n" + rules);
        assertTrue(rules.contains("- **Reason**: raw key material"), rules);
        assertTrue(rules.contains("- **Reason**: derived session key"), rules);
        assertTrue(rules.contains("- **Reason**: long-term identity key"),
            "every element keeps its own reason:\n" + rules);
    }

    @Test
    void singlePrivacyField_keepsTheHistoricalSingularForm() throws IOException {
        ProcessorTestHarness harness = granularHarness();
        harness.addSource("com.example.crypto.Solo", SINGLE_SOURCE);
        harness.compile();

        String rules = Files.readString(root.resolve(".claude/rules/com-example-crypto-Solo.md"));

        assertTrue(rules.contains("### Rules for field secret\n"
                + "- **Rule**: Never log or expose runtime values of this element.\n"
                + "- **Reason**: only one"),
            "a lone element renders exactly as before:\n" + rules);
    }

    @Test
    void roleFileSpanningTwoClasses_statesTheRuleOnce() throws IOException {
        ProcessorTestHarness harness = granularHarness();
        Files.writeString(root.resolve(".vibetags-roles"),
            "crypto-keys = **/com/example/crypto/**\n", StandardCharsets.UTF_8);
        harness.addSource("com.example.crypto.Alpha", ROLE_SOURCE_A);
        harness.addSource("com.example.crypto.Beta", ROLE_SOURCE_B);
        harness.compile();

        String rules = Files.readString(root.resolve(".claude/rules/crypto-keys.md"));

        assertEquals(1, count(rules, "Never log or expose runtime values"),
            "cross-owner duplication inside one topic file collapses too — the case #313 measured:\n" + rules);
        assertTrue(rules.contains("### com.example.crypto.Alpha.k"), rules);
        assertTrue(rules.contains("### com.example.crypto.Beta.k"), rules);
        assertTrue(rules.contains("- **Reason**: alpha key"), rules);
        assertTrue(rules.contains("- **Reason**: beta key"), rules);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
