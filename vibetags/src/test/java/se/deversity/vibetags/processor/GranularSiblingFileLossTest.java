package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A granular rule file belonging to one module, lost while a different module builds.
 *
 * <p>Each module writes its own granular files: the write plan comes from the compiling module's
 * own elements, and a sibling's stems are added only to the cleanup-exclusion set, so they are
 * protected from deletion but never rewritten. That is deliberate — letting one module write into
 * another's rule files is the reach that deleted 256 committed files in issue #383, inverted.
 *
 * <p>The cost is that a file lost to {@code git clean}, a bad merge or a granular opt-out round
 * trip stays lost until its own module recompiles, while the aggregate keeps naming it as the
 * authoritative source for the element. The guardrail is then stated nowhere: not inline, because
 * the region is collapsed to an index, and not in a rule file, because it is gone.
 *
 * <p>Restoring it here is not possible without changing the sidecar format — a
 * {@code GranularContribution} is globs plus body, carrying neither the description nor the
 * display name the renderer needs — so the build says so instead. See issue #435.
 */
@Tag("e2e")
class GranularSiblingFileLossTest {

    private static final String NL = System.lineSeparator();
    private static final String Q = String.valueOf((char) 34);

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aSiblingsRuleFileLostToGitCleanIsReportedRatherThanSilentlyLeftMissing(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-a", "com.example.a.Alpha", ctx("com.example.a", "Alpha", "alpha-routing"));
        compileModule(root, "module-b", "com.example.b.Beta", ctx("com.example.b", "Beta", "beta-routing"));

        Path betaRule = root.resolve(".claude/rules/com-example-b-Beta.md");
        assertTrue(Files.exists(betaRule), "precondition: module-b wrote its rule file");

        // Lost to git clean, a bad merge, or a granular opt-out round trip.
        Files.delete(betaRule);
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        List<String> warnings = compileModuleCapturingWarnings(root, "module-a",
            "com.example.a.Alpha", ctx("com.example.a", "Alpha", "alpha-routing"));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("com-example-b-Beta")),
            "the aggregate still names this file as authoritative, so a build that leaves it "
                + "missing has to say so. Silence is the whole defect: the guardrail is stated "
                + "nowhere and the file looks complete. Warnings were:" + NL + "  "
                + String.join(NL + "  ", warnings));

        assertFalse(Files.exists(betaRule),
            "measured limitation: a sibling's build does not restore it, because the sidecar "
                + "carries globs and body but neither the description nor the display name the "
                + "renderer needs. If this now fails, restoring has been implemented and the "
                + "warning should go with it");
    }

    /** The guard: nothing missing, nothing said. */
    @Test
    void staysSilentWhenEverySiblingRuleFileIsPresent(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-a", "com.example.a.Alpha", ctx("com.example.a", "Alpha", "alpha-routing"));
        compileModule(root, "module-b", "com.example.b.Beta", ctx("com.example.b", "Beta", "beta-routing"));

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileModuleCapturingWarnings(root, "module-a",
            "com.example.a.Alpha", ctx("com.example.a", "Alpha", "alpha-routing"));

        assertTrue(warnings.stream().noneMatch(w -> w.contains("com-example-b-Beta")),
            "an intact reactor must never see this. Warnings were:" + NL + "  "
                + String.join(NL + "  ", warnings));
    }

    /**
     * A module that opts into a granular directory of its own, in a reactor whose root has a
     * {@code .vibetags-roles} config, was reported as having lost rule files it never lost.
     *
     * <p>Two different stem namespaces meet in one sidecar field. The root contributions are
     * role-routed, so at the root this module writes {@code reactor-spine.md}. Its module-scoped
     * contributions are resolved against the module's own role config, which does not exist, so
     * they keep per-class names and are written into {@code <module>/.claude/rules/}.
     * {@code setGranularStems} stores the union of both, and the check below then looked for the
     * module-scoped names in the ROOT granular directory, where they are not supposed to be and
     * never were.
     *
     * <p>The result was a warning telling the reader their guardrails are "stated nowhere" on a
     * full, correct build that had just written every file in the right place. Found by giving
     * examples/gradle-multimodule the nested-output and role coverage of the Maven reactor
     * (issue #443); the Maven fixture never hit it because its modules have a nested aggregate
     * but no nested granular directory.
     */
    @Test
    void aModulesOwnGranularDirectoryIsNotMistakenForAMissingRootRuleFile(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        // Routes every module's classes into one shared role file at the root.
        Files.writeString(root.resolve(".vibetags-roles"), "reactor-spine = **/example/**" + NL,
            StandardCharsets.UTF_8);
        // module-a additionally opts into output of its own: aggregate AND granular directory.
        Files.createDirectories(root.resolve("module-a/.claude/rules"));
        Files.createFile(root.resolve("module-a/CLAUDE.md"));

        compileModule(root, "module-a", "com.example.a.Alpha", ctx("com.example.a", "Alpha", "alpha-routing"));
        compileModule(root, "module-b", "com.example.b.Beta", ctx("com.example.b", "Beta", "beta-routing"));

        assertTrue(Files.exists(root.resolve(".claude/rules/reactor-spine.md")),
            "precondition: the root contributions are role-routed into one shared file");
        assertTrue(Files.exists(root.resolve("module-a/.claude/rules/com-example-a-Alpha.md")),
            "precondition: module-a's own granular directory carries its per-class file");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileModuleCapturingWarnings(root, "module-a",
            "com.example.a.Alpha", ctx("com.example.a", "Alpha", "alpha-routing"));

        assertTrue(warnings.stream().noneMatch(w -> w.contains("com-example-a-Alpha")),
            "module-a's per-class rule file lives in its own granular directory, where this build "
                + "just wrote it. Reporting it as missing from the ROOT directory tells the reader "
                + "to go looking for a file that is not supposed to be there, and calls a correct "
                + "build broken. Warnings were:" + NL + "  " + String.join(NL + "  ", warnings));
    }

    private static String ctx(String pkg, String type, String focus) {
        return "package " + pkg + ";" + NL
            + "import se.deversity.vibetags.annotations.AIContext;" + NL
            + "@AIContext(focus = " + Q + focus + Q + ")" + NL
            + "public class " + type + " {}" + NL;
    }

    private static void compileModule(Path root, String module, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = newHarness(root, module, fqn, source);
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    private static List<String> compileModuleCapturingWarnings(Path root, String module, String fqn,
                                                               String source) throws IOException {
        ProcessorTestHarness harness = newHarness(root, module, fqn, source);
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.WARNING
                || d.getKind() == javax.tools.Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

    private static ProcessorTestHarness newHarness(Path root, String module, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace((char) 46, (char) 47) + ".java", source);
        return harness;
    }
}
