package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A granular rule's glob, and an ignore file's, names the file the code is in, in the language it
 * is written in (#939).
 *
 * <p>The glob was always {@code **}{@code /<Type>.java}. Under kapt and Groovy's stub generation
 * javac sees a generated {@code .java} stub, so a Kotlin class in {@code Invoice.kt} got a rule
 * scoped to {@code Invoice.java}, a file that does not exist, and Claude Code, Cursor, Copilot and
 * the other path-scoped platforms never loaded it. The stubs say which language they came from:
 * kapt keeps {@code @kotlin.Metadata} on every class, and a Groovy class implements
 * {@code groovy.lang.GroovyObject}. The KSP front end, which sees the real {@code .kt} file, is
 * covered in vibetags-ksp.
 *
 * <p>The two marker types are declared here as sources, which is all the processor reads of them.
 */
@Tag("e2e")
@DisplayName("Granular and ignore globs name the source file in its own language (#939)")
class GranularSourceFileGlobTest {

    private static final String KOTLIN_METADATA =
        "package kotlin;\npublic @interface Metadata { int k() default 1; }\n";
    private static final String GROOVY_OBJECT =
        "package groovy.lang;\npublic interface GroovyObject {}\n";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness granular(Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".claude/rules"));
        h.touchOptIn(".cursorignore");
        h.addSource("kotlin.Metadata", KOTLIN_METADATA);
        h.addSource("groovy.lang.GroovyObject", GROOVY_OBJECT);
        return h;
    }

    private static String rule(ProcessorTestHarness h, String qualifiedName) throws IOException {
        return h.readFile(".claude/rules/" + qualifiedName.replace('.', '-') + ".md");
    }

    @Test
    @DisplayName("a kapt stub's class is scoped to its .kt file")
    void aKotlinClassIsScopedToItsKtFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.Invoice", """
            package com.example;
            @kotlin.Metadata(k = 1)
            public final class Invoice {
                @se.deversity.vibetags.annotations.AILocked(reason = "wire format")
                public long total() { return 0L; }
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Invoice");
        assertTrue(rule.contains("**/Invoice.kt"), "the rule must load when Invoice.kt is opened:\n" + rule);
        assertFalse(rule.contains("Invoice.java"), "no Invoice.java exists in a Kotlin module:\n" + rule);
    }

    @Test
    @DisplayName("a Kotlin file facade (top-level functions) is scoped to the file it is named after")
    void aKotlinFileFacadeIsScopedToItsFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.BillingKt", """
            package com.example;
            @kotlin.Metadata(k = 2)
            public final class BillingKt {
                @se.deversity.vibetags.annotations.AILocked(reason = "settlement order")
                public static void settle() {}
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.BillingKt");
        assertTrue(rule.contains("**/Billing.kt"),
            "top-level functions of Billing.kt compile into BillingKt; the rule must name Billing.kt:\n" + rule);
    }

    @Test
    @DisplayName("a Groovy stub's class is scoped to its .groovy file")
    void aGroovyClassIsScopedToItsGroovyFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.Report", """
            package com.example;
            public class Report implements groovy.lang.GroovyObject {
                @se.deversity.vibetags.annotations.AILocked(reason = "audited layout")
                public void render() {}
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Report");
        assertTrue(rule.contains("**/Report.groovy"), "the rule must load when Report.groovy is opened:\n" + rule);
        assertFalse(rule.contains("Report.java"), rule);
    }

    @Test
    @DisplayName("a Java type is unchanged, and a second top-level type is scoped to the file it is in")
    void javaIsUnchangedAndASecondaryTypeNamesItsFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.Ledger", """
            package com.example;
            public class Ledger {
                @se.deversity.vibetags.annotations.AILocked(reason = "balance invariant")
                public void post() {}
            }
            class LedgerIndex {
                @se.deversity.vibetags.annotations.AILocked(reason = "index order")
                void rebuild() {}
            }
            """);

        h.compile();

        assertTrue(rule(h, "com.example.Ledger").contains("**/Ledger.java"), rule(h, "com.example.Ledger"));
        String secondary = rule(h, "com.example.LedgerIndex");
        assertTrue(secondary.contains("**/Ledger.java"),
            "LedgerIndex is declared in Ledger.java, and no LedgerIndex.java exists:\n" + secondary);
        assertFalse(secondary.contains("LedgerIndex.java"), secondary);
    }

    /**
     * Invariant 12: the file name becomes generated content, so it has to reach the build
     * fingerprint. A build whose only change is the language a type is written in must regenerate,
     * not short-circuit past a rule still scoped to the old file.
     */
    @Test
    @DisplayName("a change of source language alone regenerates the rule")
    void aLanguageChangeAloneRegenerates(@TempDir Path root) throws IOException {
        String body = """
            public final class Invoice {
                @se.deversity.vibetags.annotations.AILocked(reason = "wire format")
                public long total() { return 0L; }
            }
            """;
        ProcessorTestHarness java = granular(root);
        java.addSource("com.example.Invoice", "package com.example;\n" + body);
        java.compile();
        VibeTagsLogger.shutdown();
        assertTrue(rule(java, "com.example.Invoice").contains("**/Invoice.java"), "precondition");

        ProcessorTestHarness kotlin = granular(root);
        kotlin.addSource("com.example.Invoice", "package com.example;\n@kotlin.Metadata(k = 1)\n" + body);
        kotlin.compile();

        String rule = rule(kotlin, "com.example.Invoice");
        assertTrue(rule.contains("**/Invoice.kt") && !rule.contains("Invoice.java"),
            "the second build short-circuited on an unchanged fingerprint:\n" + rule);
    }

    @Test
    @DisplayName("an ignored Kotlin class is excluded by its .kt file")
    void anIgnoredKotlinClassIsExcludedByItsKtFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.LegacyCodec", """
            package com.example;
            @kotlin.Metadata(k = 1)
            @se.deversity.vibetags.annotations.AIIgnore(reason = "generated")
            public final class LegacyCodec {}
            """);

        h.compile();

        String ignore = h.readFile(".cursorignore");
        assertTrue(ignore.contains("**/LegacyCodec.kt"), ".cursorignore must hide the .kt file:\n" + ignore);
        assertFalse(ignore.contains("LegacyCodec.java"), ignore);
    }
}
