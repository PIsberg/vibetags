package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TESTING.md} in a reactor: one file at the root, one region per module that has test
 * guardrails, exactly like the other Markdown aggregates.
 *
 * <p>Modules compile one at a time, each seeing only its own sources, so the reactor's
 * {@code TESTING.md} exists only as a merge of sidecars. The failure this guards against is the
 * usual reactor one: the last module to compile writes its own view over everybody else's.
 */
@Tag("e2e")
class MultiModuleTestingMdTest {

    private static final String POINTER =
        "Guardrails for test code are in TESTING.md. Read it before modifying anything under a test source set.";

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static String contextSource(String pkg, String type, String focus) {
        return "package " + pkg + ";\n\n"
            + "import se.deversity.vibetags.annotations.AIContext;\n\n"
            + "@AIContext(focus = \"" + focus + "\")\n"
            + "public class " + type + " {\n}\n";
    }

    private void compile(String module, String sourceSet, String pkg, String type, String focus) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/" + sourceSet + "/java/" + pkg.replace('.', '/') + "/" + type + ".java",
            contextSource(pkg, type, focus));
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    private void buildBothModules() throws IOException {
        compile("module-a", "main", "com.example.a", "Alpha", "ALPHA-MAIN rule");
        compile("module-a", "test", "com.example.a", "AlphaTest", "ALPHA-TEST rule");
        compile("module-b", "main", "com.example.b", "Beta", "BETA-MAIN rule");
        compile("module-b", "test", "com.example.b", "BetaTest", "BETA-TEST rule");
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void twoModulesShareOneTestingMdWithARegionEach() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        buildBothModules();

        String testing = Files.readString(root.resolve("TESTING.md"));
        assertTrue(testing.contains("ALPHA-TEST rule") && testing.contains("BETA-TEST rule"),
            "both modules' test guardrails must be in the one TESTING.md:\n" + testing);
        assertFalse(testing.contains("ALPHA-MAIN rule") || testing.contains("BETA-MAIN rule"), testing);
        assertTrue(testing.contains("VIBETAGS-MODULE: module-a") && testing.contains("VIBETAGS-MODULE: module-b"),
            "each module gets its own region, as in every other Markdown aggregate:\n" + testing);

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(claude.contains("ALPHA-MAIN rule") && claude.contains("BETA-MAIN rule"), claude);
        assertFalse(claude.contains("ALPHA-TEST rule") || claude.contains("BETA-TEST rule"), claude);
    }

    /** FR-011: rebuilding one module must not erase the other's region. */
    @Test
    void rebuildingOneModuleKeepsTheOthersRegion() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        buildBothModules();

        compile("module-a", "test", "com.example.a", "AlphaTest", "ALPHA-TEST rule, reworded");

        String testing = Files.readString(root.resolve("TESTING.md"));
        assertTrue(testing.contains("ALPHA-TEST rule, reworded"), testing);
        assertTrue(testing.contains("BETA-TEST rule"), "module-b was not rebuilt and must still be there:\n" + testing);
    }

    /** One pointer per module region that gave something up, none for a module that did not. */
    @Test
    void thePointerAppearsOncePerContributingModule() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        buildBothModules();
        compile("module-c", "main", "com.example.c", "Gamma", "GAMMA-MAIN rule");

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertEquals(2, occurrences(claude, POINTER),
            "module-a and module-b moved guardrails; module-c has no test code:\n" + claude);
    }

    /**
     * Research R9. A {@code TESTING.md} inside a module is not a promise this change makes, and
     * no doc offers it. It is reachable all the same, because the per-module writer renders
     * through the same registry and builder as the root, so the thing to pin is that it behaves
     * like the root case rather than throwing or filling with the whole reactor. Left unpinned,
     * the first user to try it finds out by losing a build.
     */
    @Test
    void aTestingMdInsideAModuleTakesThatModulesTestGuardrailsOnly() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        Files.createDirectories(root.resolve("module-a"));
        Files.createFile(root.resolve("module-a/CLAUDE.md"));
        Files.createFile(root.resolve("module-a/TESTING.md"));

        buildBothModules();

        String nested = Files.readString(root.resolve("module-a/TESTING.md"));
        assertTrue(nested.contains("ALPHA-TEST rule"),
            "the module's own test guardrail belongs in the module's own TESTING.md:\n" + nested);
        assertFalse(nested.contains("BETA-TEST rule"),
            "a per-module file carries that module only, as its CLAUDE.md does:\n" + nested);
        assertFalse(nested.contains("ALPHA-MAIN rule"),
            "routing is the same rule here: main guardrails do not move:\n" + nested);

        String nestedClaude = Files.readString(root.resolve("module-a/CLAUDE.md"));
        assertTrue(nestedClaude.contains("ALPHA-MAIN rule"), nestedClaude);
        assertFalse(nestedClaude.contains("ALPHA-TEST rule"),
            "and the module's always-loaded file gave its test guardrails up:\n" + nestedClaude);
        assertEquals(1, occurrences(nestedClaude, POINTER),
            "with the pointer said once:\n" + nestedClaude);

        String rootTesting = Files.readString(root.resolve("TESTING.md"));
        assertTrue(rootTesting.contains("ALPHA-TEST rule") && rootTesting.contains("BETA-TEST rule"),
            "the root file is unaffected by a module opting in as well:\n" + rootTesting);
    }
}
