package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Broadest end-to-end coverage test: applies every one of the 39 annotations to real
 * compiled sources while {@link ProcessorTestHarness} activates every platform opt-in
 * file. Because each generated platform file routes its annotation sections through the
 * per-platform renderer and per-annotation formatter, a single compile exercises the
 * previously-thin platform switch branches in the marker formatters (e.g.
 * {@code AIStrictTypesFormatter}) and the every-annotation sections of the large
 * renderers (Claude, Gemini, Zed, Windsurf, Copilot, Cursor).
 *
 * <p>The assertions are intentionally light — the value of this test is driving the full
 * render matrix for coverage; the precise wording is verified by the per-annotation
 * end-to-end suites.
 */
class AllAnnotationsAllPlatformsEndToEndTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir);

        // --- Attribute-bearing TYPE annotations, one per class to avoid contradiction warnings ---
        harness.addSource("com.cov.Locked",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AILocked(reason = \"core logic\")\n"
            + "@AIContext(focus = \"auth flow\", avoids = \"reflection\")\n"
            + "public class Locked {}\n");

        harness.addSource("com.cov.Drafted",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIDraft(instructions = \"implement SMTP sending\")\n"
            + "public class Drafted {}\n");

        harness.addSource("com.cov.Audited",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIAudit(checkFor = {\"SQL Injection\", \"XSS\"})\n"
            + "public class Audited {}\n");

        harness.addSource("com.cov.Ignored",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIIgnore(reason = \"generated\")\n"
            + "public class Ignored {}\n");

        harness.addSource("com.cov.Core",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AICore(sensitivity = \"high\", note = \"battle tested\")\n"
            + "public class Core {}\n");

        harness.addSource("com.cov.Perf",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIPerformance(constraint = \"O(1) per call\")\n"
            + "public class Perf {}\n");

        harness.addSource("com.cov.Contracted",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIContract(reason = \"partner API SLA\")\n"
            + "public class Contracted {}\n");

        harness.addSource("com.cov.Tested",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AITestDriven(coverageGoal = 90, testLocation = \"src/test/java\","
            + " framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.MOCKITO},"
            + " mockPolicy = \"no mocks\")\n"
            + "public class Tested {}\n");

        harness.addSource("com.cov.ThreadSafe",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = \"CAS backed\")\n"
            + "public class ThreadSafe {}\n");

        harness.addSource("com.cov.Frozen",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIImmutable(note = \"value object\")\n"
            + "public final class Frozen {}\n");

        harness.addSource("com.cov.OldThing",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIDeprecated(replacedBy = \"com.cov.NewThing\", migrationGuide = \"use NewThing\","
            + " deadline = \"2030-01-01\")\n"
            + "public class OldThing {}\n");

        harness.addSource("com.cov.Monitored",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIObservability(metrics = {\"req.count\"}, traces = {\"checkout.span\"},"
            + " logs = {\"err.log\"}, note = \"critical path\")\n"
            + "public class Monitored {}\n");

        harness.addSource("com.cov.Regulated",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIRegulation(standard = \"GDPR\", clause = \"Art.17\", description = \"erasure\")\n"
            + "public class Regulated {}\n");

        harness.addSource("com.cov.Layered",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"com.cov.infra\", \"com.cov.db\"})\n"
            + "public class Layered {}\n");

        harness.addSource("com.cov.Idem",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIIdempotent(reason = \"safe to retry\")\n"
            + "public class Idem {}\n");

        harness.addSource("com.cov.Gated",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIFeatureFlag(flag = \"checkout.v2\", defaultValue = true)\n"
            + "public class Gated {}\n");

        harness.addSource("com.cov.Secured",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AISecure(aspect = \"authentication\")\n"
            + "public class Secured {}\n");

        harness.addSource("com.cov.Callers",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AICallersOnly({\"com.cov.auth\"})\n"
            + "public class Callers {}\n");

        harness.addSource("com.cov.Budgeted",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIMemoryBudget(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION)\n"
            + "public class Budgeted {}\n");

        harness.addSource("com.cov.Domain",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIDomainModel(allow = {\"java.util\"})\n"
            + "public class Domain {}\n");

        harness.addSource("com.cov.Extending",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)\n"
            + "public class Extending {}\n");

        harness.addSource("com.cov.Explained",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AIExplain(AIExplain.ComplexityLevel.HIGH)\n"
            + "public class Explained {}\n");

        harness.addSource("com.cov.Sunsetting",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AISunset(replacement = Object.class, jira = \"DEBT-742\")\n"
            + "public class Sunsetting {}\n");

        harness.addSource("com.cov.Temp",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AITemporary(expiresOn = \"2030-01-01\", reason = \"upstream downtime\")\n"
            + "public class Temp {}\n");

        // --- Marker (no-attribute) annotations stacked on a single class ---
        harness.addSource("com.cov.MarkerBag",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "@AILegacyBridge\n"
            + "@AIStrictClasspath\n"
            + "@AIInternationalized\n"
            + "@AIPublicAPI\n"
            + "@AISchemaSafe\n"
            + "@AIStrictExceptions\n"
            + "@AIStrictTypes\n"
            + "@AIParallelTests\n"
            + "@AISandboxOnly\n"
            + "@AIPrototype\n"
            + "public class MarkerBag {}\n");

        // --- METHOD-only and FIELD/PARAMETER-target annotations ---
        harness.addSource("com.cov.Members",
            "package com.cov;\n"
            + "import se.deversity.vibetags.annotations.*;\n"
            + "public class Members {\n"
            + "    @AIPrivacy(reason = \"PII\")\n"
            + "    private String email;\n"
            + "    @AISecureLogging(AISecureLogging.MaskingPolicy.HASH)\n"
            + "    private String token;\n"
            + "    @AIPure\n"
            + "    public int add(int a, int b) { return a + b; }\n"
            + "    public void store(@AIInputSanitized({AIInputSanitized.SanitizerType.SQL_INJECTION}) String in) {}\n"
            + "}\n");

        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void everyPlatformFileWasGenerated() {
        assertTrue(harness.fileExists(".cursorrules"), ".cursorrules should exist");
        assertTrue(harness.fileExists("CLAUDE.md"), "CLAUDE.md should exist");
        assertTrue(harness.fileExists("AGENTS.md"), "AGENTS.md should exist");
        assertTrue(harness.fileExists("QWEN.md"), "QWEN.md should exist");
        assertTrue(harness.fileExists("gemini_instructions.md"), "gemini_instructions.md should exist");
        assertTrue(harness.fileExists("GEMINI.md"), "GEMINI.md should exist");
        assertTrue(harness.fileExists(".github/copilot-instructions.md"), "copilot instructions should exist");
        assertTrue(harness.fileExists("CONVENTIONS.md"), "CONVENTIONS.md should exist");
        assertTrue(harness.fileExists("llms.txt"), "llms.txt should exist");
        assertTrue(harness.fileExists("llms-full.txt"), "llms-full.txt should exist");
        assertTrue(harness.fileExists(".windsurfrules"), ".windsurfrules should exist");
        assertTrue(harness.fileExists(".rules"), ".rules (Zed) should exist");
        assertTrue(harness.fileExists(".interpreter/profiles/vibetags.yaml"), "interpreter profile should exist");
    }

    @Test
    void strictTypesMarkerRenderedAcrossPlatforms() throws IOException {
        // The marker @AIStrictTypes on MarkerBag must surface in several platform files,
        // proving the per-platform switch branches in AIStrictTypesFormatter fired.
        assertTrue(harness.readFile(".cursorrules").contains("MarkerBag"), "cursor must mention MarkerBag");
        assertTrue(harness.readFile("CLAUDE.md").contains("strict_types_elements"),
            "CLAUDE.md must contain the strict_types section");
        assertTrue(harness.readFile(".rules").contains("MarkerBag"), "Zed .rules must mention MarkerBag");
        assertTrue(harness.readFile("llms-full.txt").contains("MarkerBag"), "llms-full must mention MarkerBag");
    }

    @Test
    void claudeContainsEverySectionForAllAnnotations() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("locked_files"), "locked_files section");
        assertTrue(claude.contains("contextual_instructions"), "context section");
        assertTrue(claude.contains("audit_requirements"), "audit section");
        assertTrue(claude.contains("ignored_elements"), "ignore section");
        assertTrue(claude.contains("implementation_tasks"), "draft section");
        assertTrue(claude.contains("pii_guardrails"), "privacy section");
        assertTrue(claude.contains("core_elements"), "core section");
        assertTrue(claude.contains("performance_constraints"), "performance section");
        assertTrue(claude.contains("contract_signatures"), "contract section");
        assertTrue(claude.contains("thread_safe_elements"), "thread-safe section");
        assertTrue(claude.contains("deprecated_elements"), "deprecated section");
        assertTrue(claude.contains("regulatory_elements"), "regulation section");
        assertTrue(claude.contains("architecture_elements"), "architecture section");
        assertTrue(claude.contains("security_elements"), "secure section");
        assertTrue(claude.contains("feature_flag_elements"), "feature flag section");
        assertTrue(claude.contains("sunset_elements"), "sunset section");
        assertTrue(claude.contains("temporary_workarounds"), "temporary section");
    }

    @Test
    void sharedInstructionBlockPlatformsIncludeNewestAnnotations() throws IOException {
        // Regression test: GuardrailInstructionBlock (shared by CodeRabbit, Ellipsis, PR-Agent,
        // and Roo modes) once stopped after @AISecure and silently dropped the twelve newest
        // annotations. Assert each of those twelve is reachable from every platform that embeds
        // the shared instruction block.
        String[] newestAnnotationElements = {
            "Callers",     // @AICallersOnly
            "MarkerBag",   // @AISandboxOnly, @AIPrototype
            "Budgeted",    // @AIMemoryBudget
            "Members",     // @AIPure, @AISecureLogging, @AIInputSanitized
            "Domain",      // @AIDomainModel
            "Extending",   // @AIExtensible
            "Explained",   // @AIExplain
            "Sunsetting",  // @AISunset
            "Temp",        // @AITemporary
        };

        for (String file : new String[]{".coderabbit.yaml", "ellipsis.yaml", ".pr_agent.toml", ".roomodes"}) {
            String content = harness.readFile(file);
            for (String element : newestAnnotationElements) {
                assertTrue(content.contains(element), file + " must mention " + element);
            }
        }
    }

    @Test
    void generatedFilesPreserveHandAuthoredContentMarkers() throws IOException {
        // Sanity: marker-based files keep generated content fenced, never empty.
        String cursor = harness.readFile(".cursorrules");
        assertFalse(cursor.isBlank(), ".cursorrules must not be blank");
        assertTrue(cursor.contains("VIBETAGS-START") || cursor.contains("AUTO-GENERATED"),
            ".cursorrules must carry generated markers/header");
    }
}
