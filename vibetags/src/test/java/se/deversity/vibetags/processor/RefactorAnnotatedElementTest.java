package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Refactoring-driven tests: what happens to generated guardrail files when a developer
 * renames, moves, or deletes the class / method / field that carries a VibeTags annotation.
 *
 * <p>Each test runs the processor twice in the same project root — first against the
 * "before refactor" sources, then against the "after refactor" sources — and asserts that
 * the second compile updates shared files and cleans up stale granular files so the
 * generated output tracks the new code shape exactly. This is the real-world IDE workflow:
 * edit code, recompile, regenerate.
 *
 * <p>Granular filenames are derived from the fully-qualified class name with dots replaced
 * by dashes (e.g. {@code com.example.payment.PaymentProcessor} →
 * {@code com-example-payment-PaymentProcessor.mdc}).
 */
class RefactorAnnotatedElementTest {

    @AfterEach
    void releaseLogHandle() {
        // Release the log file handle so a second harness (and Windows) can reuse the dir.
        VibeTagsLogger.shutdown();
    }

    /**
     * Builds a default harness that ALSO opts into {@code .cursor/rules}, so the Cursor granular
     * scoped files are produced. The aggregate ({@code .cursorrules} / {@code CLAUDE.md}) therefore
     * collapses to a scoped-rules index: refactor tracking is asserted via the index owner FQNs
     * (locked and privacy detail stays inline; per-method contract detail lives in the scoped file).
     */
    private static ProcessorTestHarness granularHarness(Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir);
        h.touchOptIn(".cursor/rules/.vibetags");
        return h;
    }

    // -----------------------------------------------------------------------
    // Rename an annotated class
    // -----------------------------------------------------------------------

    @Test
    void renamingAnnotatedClass_movesGranularFileAndUpdatesSharedRules(@TempDir Path dir) throws Exception {
        // --- before: class is named PaymentProcessor ---
        ProcessorTestHarness before = granularHarness(dir);
        before.addSource("com.example.payment.PaymentProcessor",
            "package com.example.payment;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"Core payment logic\")\n" +
            "public class PaymentProcessor {}\n");
        before.compile();

        assertTrue(before.fileExists(".cursor/rules/com-example-payment-PaymentProcessor.mdc"),
            "Granular rule for the original class name must exist after first compile");
        assertTrue(before.readFile(".cursorrules").contains("PaymentProcessor"),
            "Shared .cursorrules must reference the original class name");

        VibeTagsLogger.shutdown();
        ProcessorTestHarness.awaitFilesystemTick(dir);

        // --- after: developer renames the class to PaymentService ---
        ProcessorTestHarness after = granularHarness(dir);
        after.addSource("com.example.payment.PaymentService",
            "package com.example.payment;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"Core payment logic\")\n" +
            "public class PaymentService {}\n");
        after.compile();

        assertFalse(after.fileExists(".cursor/rules/com-example-payment-PaymentProcessor.mdc"),
            "Granular rule for the OLD class name must be cleaned up after rename");
        assertTrue(after.fileExists(".cursor/rules/com-example-payment-PaymentService.mdc"),
            "Granular rule for the NEW class name must be generated");

        String cursorRules = after.readFile(".cursorrules");
        assertTrue(cursorRules.contains("PaymentService"),
            "Shared .cursorrules must reference the new class name");
        assertFalse(cursorRules.contains("PaymentProcessor"),
            "Shared .cursorrules must no longer reference the renamed-away class");
    }

    // -----------------------------------------------------------------------
    // Move an annotated class to a different package
    // -----------------------------------------------------------------------

    @Test
    void movingAnnotatedClassToNewPackage_relocatesGranularFileAndUpdatesFqn(@TempDir Path dir) throws Exception {
        // --- before: com.example.payment.OrderManager ---
        ProcessorTestHarness before = granularHarness(dir);
        before.addSource("com.example.payment.OrderManager",
            "package com.example.payment;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"Order lifecycle invariants\")\n" +
            "public class OrderManager {}\n");
        before.compile();

        assertTrue(before.fileExists(".cursor/rules/com-example-payment-OrderManager.mdc"),
            "Granular rule under the original package must exist after first compile");
        assertTrue(before.readFile("CLAUDE.md").contains("com.example.payment.OrderManager"),
            "CLAUDE.md must reference the original fully-qualified name");

        VibeTagsLogger.shutdown();
        ProcessorTestHarness.awaitFilesystemTick(dir);

        // --- after: same class, moved to com.example.billing ---
        ProcessorTestHarness after = granularHarness(dir);
        after.addSource("com.example.billing.OrderManager",
            "package com.example.billing;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"Order lifecycle invariants\")\n" +
            "public class OrderManager {}\n");
        after.compile();

        assertFalse(after.fileExists(".cursor/rules/com-example-payment-OrderManager.mdc"),
            "Granular rule under the OLD package path must be cleaned up after the move");
        assertTrue(after.fileExists(".cursor/rules/com-example-billing-OrderManager.mdc"),
            "Granular rule under the NEW package path must be generated");

        String claudeMd = after.readFile("CLAUDE.md");
        assertTrue(claudeMd.contains("com.example.billing.OrderManager"),
            "CLAUDE.md must reference the new fully-qualified name");
        assertFalse(claudeMd.contains("com.example.payment.OrderManager"),
            "CLAUDE.md must no longer reference the old package path");
    }

    // -----------------------------------------------------------------------
    // Rename an annotated method
    // -----------------------------------------------------------------------

    @Test
    void renamingAnnotatedMethod_updatesContractPathInSharedAndGranularFiles(@TempDir Path dir) throws Exception {
        // --- before: @AIContract on PaymentGateway.charge ---
        ProcessorTestHarness before = granularHarness(dir);
        before.addSource("com.example.api.PaymentGateway",
            "package com.example.api;\n" +
            "import se.deversity.vibetags.annotations.AIContract;\n" +
            "public interface PaymentGateway {\n" +
            "    @AIContract(reason = \"Partner API SLA\")\n" +
            "    double charge(String customerId, double amount);\n" +
            "}\n");
        before.compile();

        assertTrue(before.readFile(".cursor/rules/com-example-api-PaymentGateway.mdc").contains("charge"),
            "Cursor granular rule must reference the original method name");
        assertTrue(before.readFile("CLAUDE.md").contains("com.example.api.PaymentGateway"),
            "CLAUDE.md scoped-rules index must reference the class that owns the contract");

        VibeTagsLogger.shutdown();
        ProcessorTestHarness.awaitFilesystemTick(dir);

        // --- after: charge() renamed to settle() ---
        ProcessorTestHarness after = granularHarness(dir);
        after.addSource("com.example.api.PaymentGateway",
            "package com.example.api;\n" +
            "import se.deversity.vibetags.annotations.AIContract;\n" +
            "public interface PaymentGateway {\n" +
            "    @AIContract(reason = \"Partner API SLA\")\n" +
            "    double settle(String customerId, double amount);\n" +
            "}\n");
        after.compile();

        // Aggregate CLAUDE.md is a scoped-rules index (granular sibling opted in), so per-method
        // contract detail lives in the scoped file; the index references only the owning class.
        String granular = after.readFile(".cursor/rules/com-example-api-PaymentGateway.mdc");
        assertFalse(granular.isEmpty(), "Granular rule for the class must still exist after a method rename");
        assertTrue(granular.contains("settle"), "Granular rule must reference the renamed method");
        assertFalse(granular.contains("charge"), "Granular rule must no longer reference the old method name");

        assertTrue(after.readFile("CLAUDE.md").contains("com.example.api.PaymentGateway"),
            "CLAUDE.md scoped-rules index must still reference the contract's owning class");
    }

    // -----------------------------------------------------------------------
    // Rename an annotated field
    // -----------------------------------------------------------------------

    @Test
    void renamingAnnotatedField_updatesPrivacyElementPath(@TempDir Path dir) throws Exception {
        // --- before: @AIPrivacy on UserProfile.ssn ---
        ProcessorTestHarness before = granularHarness(dir);
        before.addSource("com.example.UserProfile",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AIPrivacy;\n" +
            "public class UserProfile {\n" +
            "    @AIPrivacy(reason = \"PII - GDPR protected\")\n" +
            "    private String ssn;\n" +
            "}\n");
        before.compile();

        assertTrue(before.readFile("CLAUDE.md").contains("com.example.UserProfile.ssn"),
            "CLAUDE.md privacy section must reference the original field path");

        VibeTagsLogger.shutdown();
        ProcessorTestHarness.awaitFilesystemTick(dir);

        // --- after: field ssn renamed to taxId (non-overlapping names) ---
        ProcessorTestHarness after = granularHarness(dir);
        after.addSource("com.example.UserProfile",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AIPrivacy;\n" +
            "public class UserProfile {\n" +
            "    @AIPrivacy(reason = \"PII - GDPR protected\")\n" +
            "    private String taxId;\n" +
            "}\n");
        after.compile();

        String claudeMd = after.readFile("CLAUDE.md");
        assertTrue(claudeMd.contains("com.example.UserProfile.taxId"),
            "CLAUDE.md privacy section must reference the renamed field path");
        assertFalse(claudeMd.contains("com.example.UserProfile.ssn"),
            "CLAUDE.md privacy section must no longer reference the old field path");
    }

    // -----------------------------------------------------------------------
    // Remove the annotation entirely (refactor the guardrail away)
    // -----------------------------------------------------------------------

    @Test
    void removingAnnotationFromClass_cleansGranularFileAndDropsFromSharedRules(@TempDir Path dir) throws Exception {
        // A sibling stays annotated throughout so the module always has rules and shared
        // files keep regenerating (a module with zero annotations is intentionally skipped).
        ProcessorTestHarness before = granularHarness(dir);
        before.addSource("com.example.LegacyCache",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"Cache coherence is load-bearing\")\n" +
            "public class LegacyCache {}\n");
        before.addSource("com.example.Anchor",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AIContext;\n" +
            "@AIContext(focus = \"keeps the module non-empty\", avoids = \"nothing\")\n" +
            "public class Anchor {}\n");
        before.compile();

        assertTrue(before.fileExists(".cursor/rules/com-example-LegacyCache.mdc"),
            "Granular rule must exist while the class is annotated");
        assertTrue(before.readFile(".cursorrules").contains("LegacyCache"),
            "Shared .cursorrules must reference the annotated class");

        VibeTagsLogger.shutdown();
        ProcessorTestHarness.awaitFilesystemTick(dir);

        // --- after: developer deletes the @AILocked annotation from LegacyCache ---
        ProcessorTestHarness after = granularHarness(dir);
        after.addSource("com.example.LegacyCache",
            "package com.example;\n" +
            "public class LegacyCache {}\n");
        after.addSource("com.example.Anchor",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AIContext;\n" +
            "@AIContext(focus = \"keeps the module non-empty\", avoids = \"nothing\")\n" +
            "public class Anchor {}\n");
        after.compile();

        assertFalse(after.fileExists(".cursor/rules/com-example-LegacyCache.mdc"),
            "Granular rule must be cleaned up once the annotation is removed");
        String cursorRules = after.readFile(".cursorrules");
        assertFalse(cursorRules.contains("LegacyCache"),
            "Shared .cursorrules must drop the de-annotated class");
        assertTrue(cursorRules.contains("Anchor"),
            "The still-annotated sibling must remain in shared rules");
    }

    @Test
    void deletingAnnotatedClass_cleansUpItsGranularFile(@TempDir Path dir) throws Exception {
        ProcessorTestHarness before = granularHarness(dir);
        before.addSource("com.example.TempExperiment",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"spike\")\n" +
            "public class TempExperiment {}\n");
        before.addSource("com.example.Keeper",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"keep me\")\n" +
            "public class Keeper {}\n");
        before.compile();

        assertTrue(before.fileExists(".cursor/rules/com-example-TempExperiment.mdc"));
        assertTrue(before.fileExists(".cursor/rules/com-example-Keeper.mdc"));

        VibeTagsLogger.shutdown();
        ProcessorTestHarness.awaitFilesystemTick(dir);

        // --- after: the whole TempExperiment class is deleted; Keeper survives ---
        ProcessorTestHarness after = granularHarness(dir);
        after.addSource("com.example.Keeper",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"keep me\")\n" +
            "public class Keeper {}\n");
        after.compile();

        assertFalse(after.fileExists(".cursor/rules/com-example-TempExperiment.mdc"),
            "Granular rule for the deleted class must be cleaned up");
        assertTrue(after.fileExists(".cursor/rules/com-example-Keeper.mdc"),
            "Granular rule for the surviving class must remain");
        assertFalse(after.readFile(".cursorrules").contains("TempExperiment"),
            "Shared .cursorrules must drop the deleted class");
    }
}
