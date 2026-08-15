package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the Gherkin scenarios in {@code src/test/resources/features/} against a real
 * compile via {@link ProcessorTestHarness}.
 *
 * <p>Deliberately dependency-free: the parent is on JUnit Platform 6 and Cucumber's engine
 * targets Platform 1.x, so instead of a framework this test parses scenario titles itself and
 * binds each to an executable block. The binding is scenario-level, not step-level; the step
 * lines are the human-readable specification, and the binding must implement all of them. The
 * contract that keeps the feature file honest runs both ways: a scenario with no binding fails
 * the build, and a binding with no scenario fails the build, so prose and execution cannot
 * drift apart silently.
 */
@Tag("e2e")
class CoreFlowsBddTest {

    private static final String FEATURE = "/features/core-guardrail-flows.feature";

    @TempDir
    static Path soleOptInDir;

    @TempDir
    static Path handContentDir;

    @TempDir
    static Path optOutDir;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @TestFactory
    List<DynamicTest> everyScenarioInTheFeatureFileRuns() throws IOException {
        List<String> scenarios = scenarioTitles();
        Map<String, ScenarioBinding> bindings = bindings();

        assertEquals(bindings.keySet(), new java.util.LinkedHashSet<>(scenarios),
            "feature file and bindings disagree; a scenario without a binding is fiction, "
                + "and a binding without a scenario is dead code");

        List<DynamicTest> tests = new ArrayList<>();
        for (String title : scenarios) {
            ScenarioBinding binding = bindings.get(title);
            tests.add(DynamicTest.dynamicTest(title, binding::run));
        }
        return tests;
    }

    // -----------------------------------------------------------------------

    @FunctionalInterface
    private interface ScenarioBinding {
        void run() throws Exception;
    }

    private Map<String, ScenarioBinding> bindings() {
        Map<String, ScenarioBinding> map = new LinkedHashMap<>();

        map.put("File presence is the only opt-in", () -> {
            ProcessorTestHarness h =
                ProcessorTestHarness.withExampleSourcesSoleOptIn(soleOptInDir, "CLAUDE.md");
            String content = h.readFile("CLAUDE.md");
            assertTrue(content.contains("VIBETAGS-START"),
                "CLAUDE.md should contain a generated guardrail block");
            assertFalse(h.fileExists(".cursorrules"),
                ".cursorrules was never opted in and must not be created");
            assertFalse(h.fileExists("GEMINI.md"),
                "GEMINI.md was never opted in and must not be created");
        });

        map.put("Hand-authored content survives regeneration", () -> {
            ProcessorTestHarness h =
                ProcessorTestHarness.withExampleSourcesSoleOptIn(handContentDir, "CLAUDE.md");
            Path claudeMd = handContentDir.resolve("CLAUDE.md");
            String generated = Files.readString(claudeMd, StandardCharsets.UTF_8);
            Files.writeString(claudeMd,
                "Hand-written notes above the block.\n\n" + generated
                    + "\nHand-written notes below the block.\n",
                StandardCharsets.UTF_8);
            ProcessorTestHarness.awaitFilesystemTick(handContentDir);
            h.compile();
            String after = h.readFile("CLAUDE.md");
            assertTrue(after.contains("Hand-written notes above the block."),
                "hand content above the markers must survive regeneration");
            assertTrue(after.contains("Hand-written notes below the block."),
                "hand content below the markers must survive regeneration");
            assertTrue(after.contains("VIBETAGS-START"),
                "the generated block must still be present after regeneration");
        });

        map.put("Deleting a generated file opts the platform out permanently", () -> {
            ProcessorTestHarness h =
                ProcessorTestHarness.withExampleSourcesSoleOptIn(optOutDir, "CLAUDE.md");
            assertTrue(h.fileExists("CLAUDE.md"), "precondition: the opted-in file exists");
            Files.delete(optOutDir.resolve("CLAUDE.md"));
            ProcessorTestHarness.awaitFilesystemTick(optOutDir);
            h.compile();
            assertFalse(h.fileExists("CLAUDE.md"),
                "a deleted output file is the opt-out signal and must not be recreated");
        });

        return map;
    }

    /** Scenario titles, in file order, from the {@code Scenario:} lines of the feature file. */
    private List<String> scenarioTitles() throws IOException {
        try (InputStream in = CoreFlowsBddTest.class.getResourceAsStream(FEATURE)) {
            assertNotNull(in, "feature file missing from the test classpath: " + FEATURE);
            List<String> titles = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String stripped = line.strip();
                if (stripped.startsWith("Scenario:")) {
                    titles.add(stripped.substring("Scenario:".length()).strip());
                }
            }
            return titles;
        }
    }
}
