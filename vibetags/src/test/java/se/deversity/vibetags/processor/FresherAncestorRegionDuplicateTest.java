package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An ancestor region whose elements are fully covered by a nested module is retired even when it
 * is the <em>fresher</em> sidecar of the two.
 *
 * <p>{@code AncestorModuleDuplicateRegionTest} covers the case where the nested module compiled
 * last, and there the ancestor is retired. The prune consults sidecar timestamps before it
 * consults containment, so it only lets a descendant retire its ancestor when the descendant is at
 * least as fresh. Reverse the order and nothing is retired at all:
 *
 * <ul>
 *   <li>the ancestor cannot be retired, because its only descendant is older;</li>
 *   <li>the descendant cannot be retired either, because the ancestor's smaller element set does
 *       not cover the descendant's.</li>
 * </ul>
 *
 * <p>Both regions therefore survive, and every element they share is written twice: once under
 * each identity, byte-identical, into every generated rule file and into the aggregate. The give
 * away in a report is that <em>some</em> elements duplicate and others do not. The ones that
 * duplicate are exactly the ancestor's smaller set, which reads like an annotation-specific bug
 * and is nothing of the kind.
 *
 * <p>Ordering is not exotic. The ancestor identity is what a compilation falls back to when the
 * module root cannot be resolved from the round's sources, so any later build that takes the
 * fallback path writes it fresh, and it then outranks the real module's sidecar for good.
 *
 * <p>Timestamps here are set explicitly rather than left to the order of the two compiles.
 * Filesystem mtime granularity is coarse enough on some hosts to make two writes a second apart
 * compare equal, and the equal case is already covered elsewhere. This test is about the strictly
 * newer ancestor.
 */
@Tag("e2e")
@DisplayName("A fresher ancestor region still yields to the module nested inside it")
class FresherAncestorRegionDuplicateTest {

    private static final String VALIDATOR_SOURCE = """
        package com.example.billing;

        import se.deversity.vibetags.annotations.AISecure;

        @AISecure(aspect = "input validation")
        public class InvoiceValidator {
        }
        """;

    private static final String WRITER_SOURCE = """
        package com.example.billing;

        import se.deversity.vibetags.annotations.AIContract;

        @AIContract(invariants = "Ledger entries are append-only")
        public class LedgerWriter {
        }
        """;

    private static final String CALCULATOR_SOURCE = """
        package com.example.billing;

        import se.deversity.vibetags.annotations.AICore;

        @AICore(sensitivity = "critical")
        public class TaxCalculator {
        }
        """;

    @TempDir
    Path repoRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /**
     * Builds the reported state: a nested module sidecar holding three elements, and an ancestor
     * sidecar holding two of them, written afterwards and strictly newer.
     */
    private void buildFresherAncestorOverNestedModule() throws IOException {
        Files.writeString(repoRoot.resolve("settings.gradle"),
            "rootProject.name = 'app'\ninclude 'app'\n", StandardCharsets.UTF_8);
        Files.writeString(repoRoot.resolve("build.gradle"),
            "plugins { id 'java' }\n", StandardCharsets.UTF_8);
        Files.createDirectories(repoRoot.resolve(".gemini/rules"));

        // The subproject has its own build file, so the module-root walk stops there.
        Files.createDirectories(repoRoot.resolve("app"));
        Files.writeString(repoRoot.resolve("app/build.gradle"),
            "plugins { id 'java' }\n", StandardCharsets.UTF_8);

        ProcessorTestHarness nested = new ProcessorTestHarness(repoRoot, false);
        nested.writeSourceFile("app/src/main/java/com/example/billing/InvoiceValidator.java",
            VALIDATOR_SOURCE);
        nested.writeSourceFile("app/src/main/java/com/example/billing/LedgerWriter.java",
            WRITER_SOURCE);
        nested.writeSourceFile("app/src/main/java/com/example/billing/TaxCalculator.java",
            CALCULATOR_SOURCE);
        nested.compile();

        // Backdated before the second compile rather than after it. The merge that has to make
        // the decision runs inside that compile, so the relation has to hold by then, and mtime
        // granularity is coarse enough on some hosts to make two consecutive writes compare equal.
        backdateNestedSidecar();

        // Now a build that cannot resolve the subproject: its build file is gone, so the walk
        // climbs to the repository root and the compilation files itself under the ancestor
        // identity. It sees two of the three elements, so its set is a strict subset.
        Files.delete(repoRoot.resolve("app/build.gradle"));
        Files.delete(repoRoot.resolve(
            "app/src/main/java/com/example/billing/TaxCalculator.java"));

        ProcessorTestHarness ancestor = new ProcessorTestHarness(repoRoot, false);
        ancestor.writeSourceFile("app/src/main/java/com/example/billing/InvoiceValidator.java",
            VALIDATOR_SOURCE);
        ancestor.writeSourceFile("app/src/main/java/com/example/billing/LedgerWriter.java",
            WRITER_SOURCE);
        ancestor.compile();
    }

    /** Puts the nested module's sidecar strictly in the past, so the ancestor is the fresher. */
    private void backdateNestedSidecar() throws IOException {
        Path nested = repoRoot.resolve(".vibetags-mod-app");
        assertTrue(Files.exists(nested), "precondition: the nested module wrote its sidecar");
        long now = Files.getLastModifiedTime(nested).toMillis();
        Files.setLastModifiedTime(nested, FileTime.fromMillis(now - 60_000L));
    }

    private String ruleFile(String stem) throws IOException {
        Path p = repoRoot.resolve(".gemini/rules/" + stem + ".md");
        assertTrue(Files.exists(p), "expected generated rule file " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("a shared element is stated once, not once per identity")
    void sharedElementsAreNotWrittenTwice() throws IOException {
        buildFresherAncestorOverNestedModule();

        String validator = ruleFile("com-example-billing-InvoiceValidator");
        assertEquals(1, countOf(validator, "## Security-Critical Code"),
            "the guardrail is stated once per element, not once per module identity. Two regions "
                + "claiming the same element write it twice, byte-identical:\n" + validator);

        String writer = ruleFile("com-example-billing-LedgerWriter");
        assertEquals(1, countOf(writer, "## Contract-Frozen Signature"),
            "the guardrail is stated once per element, not once per module identity:\n" + writer);
    }

    @Test
    @DisplayName("the covered ancestor sidecar is retired even though it is the newer file")
    void theFresherAncestorSidecarIsPruned() throws IOException {
        buildFresherAncestorOverNestedModule();

        assertFalse(Files.exists(repoRoot.resolve(".vibetags-mod-_root_")),
            "the ancestor holds no element the nested module does not, so it is the same sources "
                + "under a less specific identity and must be retired. Being the more recently "
                + "written file does not make it right: the ancestor identity is the fallback a "
                + "compilation takes when it cannot resolve its module root, so it is precisely "
                + "the one most likely to be both wrong and recent.");
        assertTrue(Files.exists(repoRoot.resolve(".vibetags-mod-app")),
            "the real module's sidecar must survive the prune");
    }

    @Test
    @DisplayName("one surviving region leaves no module markers behind")
    void oneRegionMeansNoSubMarkers() throws IOException {
        buildFresherAncestorOverNestedModule();

        String validator = ruleFile("com-example-billing-InvoiceValidator");
        assertFalse(validator.contains("VIBETAGS-MODULE"),
            "one module leaves one region, so no sub-markers belong in the file:\n" + validator);
    }

    /**
     * The element only the nested module ever saw must not be collateral damage. It is the
     * element that distinguishes the two sets, so a fix that retires the wrong region loses it.
     */
    @Test
    @DisplayName("an element the ancestor never saw survives the prune")
    void theElementOnlyTheNestedModuleSawSurvives() throws IOException {
        buildFresherAncestorOverNestedModule();

        String calculator = ruleFile("com-example-billing-TaxCalculator");
        assertEquals(1, countOf(calculator, "critical"),
            "the nested module's own element must still be rendered:\n" + calculator);
    }
}
