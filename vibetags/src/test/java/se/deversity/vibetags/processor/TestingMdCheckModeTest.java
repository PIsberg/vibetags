package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Check mode ({@code -Avibetags.check=true}) and {@code TESTING.md}.
 *
 * <p>A check verdict is worth something only while it reproduces generation. Routing adds two
 * ways for the two to part: check mode rendering a test round unrouted while generation routes it,
 * which reports drift on a tree a real build just produced, and check mode missing the unrouted
 * fallback, which reports drift after {@code TESTING.md} is deleted. Both share
 * {@code populateSidecarBodies} and {@code mergeAcrossModules} with generation for exactly this
 * reason; these cases are what holds them to it.
 */
@Tag("e2e")
class TestingMdCheckModeTest {

    private static final String CHECK_OPTION = "-Avibetags.check=true";
    private static final String CHECK_FAILED = "VibeTags: check failed";

    @TempDir
    Path root;

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    private static String contextSource(String type, String focus) {
        return "package com.example.ledger;\n\n"
            + "import se.deversity.vibetags.annotations.AIContext;\n\n"
            + "@AIContext(focus = \"" + focus + "\")\n"
            + "public class " + type + " {\n}\n";
    }

    private ProcessorTestHarness harness(String sourceSet, String type, String focus) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("src/" + sourceSet + "/java/com/example/ledger/" + type + ".java",
            contextSource(type, focus));
        return harness;
    }

    private void build(String sourceSet, String type, String focus) throws IOException {
        harness(sourceSet, type, focus).compile();
        VibeTagsLogger.shutdown();
    }

    private List<String> checkErrors(String sourceSet, String type, String focus) throws IOException {
        List<Diagnostic<? extends JavaFileObject>> diags =
            harness(sourceSet, type, focus).compileReturningDiagnostics(CHECK_OPTION);
        VibeTagsLogger.shutdown();
        return diags.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .collect(Collectors.toList());
    }

    private void fullRoutedBuild() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        build("main", "Ledger", "Ledger totals are integer minor units");
        build("test", "LedgerTest", "Fixtures build ledgers through LedgerBuilder only");
    }

    @Test
    void checkAgreesWithARoutedBuild_forBothRounds() throws IOException {
        fullRoutedBuild();

        assertEquals(List.of(), checkErrors("test", "LedgerTest", "Fixtures build ledgers through LedgerBuilder only"),
            "check mode reported drift on the tree the routed test round just produced");
        assertEquals(List.of(), checkErrors("main", "Ledger", "Ledger totals are integer minor units"),
            "check mode reported drift on the tree the main round just produced");
    }

    @Test
    void aStaleTestingMdIsDrift_andCheckModeLeavesItAlone() throws IOException {
        fullRoutedBuild();
        String testingBefore = Files.readString(root.resolve("TESTING.md"));

        List<String> errors = checkErrors("test", "LedgerTest", "Fixtures use the in-memory ledger store");

        assertTrue(errors.stream().anyMatch(m -> m.contains(CHECK_FAILED) && m.contains("TESTING.md")),
            "a changed test annotation makes TESTING.md stale, and check mode must name it: " + errors);
        assertEquals(testingBefore, Files.readString(root.resolve("TESTING.md")), "check mode writes nothing");
    }

    @Test
    void checkAgreesWithGenerationAfterTestingMdIsDeletedAndOnlyMainIsRebuilt() throws IOException {
        fullRoutedBuild();
        Files.delete(root.resolve("TESTING.md"));
        build("main", "Ledger", "Ledger totals are integer minor units");
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("Fixtures build ledgers"),
            "precondition: the fallback put the test guardrail back");

        assertEquals(List.of(), checkErrors("main", "Ledger", "Ledger totals are integer minor units"),
            "check mode must read the same unrouted fallback generation read");
    }
}
