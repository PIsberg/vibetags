package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.model.RoleConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The always-loaded safety tier for Cline's {@code .clinerules/} directory form (issue #648).
 *
 * <p>Every per-element rule file in the directory carries {@code paths:} front matter, so Cline
 * loads it only once a matching file is in the task's context. That is the right model for the
 * verbose tier and the wrong one for the six safety buckets: invariant 6 keeps {@code @AILocked},
 * {@code @AICore}, {@code @AIPrivacy}, {@code @AIIgnore}, {@code @AIAudit} and {@code @AISecure}
 * always loaded, because a guardrail that arrives only after the agent opened the file it protects
 * has become a comment. Other platforms get that from their aggregate; Cline's aggregate and its
 * directory are one path, so a directory-only project had no always-loaded file at all.
 *
 * <p>The fix is one file in the directory with no front matter. Cline's
 * {@code rule-conditionals.ts} evaluates only the keys a rule's front matter declares, so a rule
 * with none passes unconditionally, and its docs state it: "Rules without frontmatter are always
 * active."
 */
@Tag("e2e")
class ClineSafetyTierEndToEndTest {

    private static final String SAFETY_FILE = ".clinerules/" + ServiceRegistry.CLINE_SAFETY_FILE;

    private static final String USER_SOURCE = """
        package com.example.user;

        import se.deversity.vibetags.annotations.AIPrivacy;

        public class UserRecord {
            @AIPrivacy(reason = "Email is PII - never log it")
            private String email;
        }
        """;

    private static final String LOCKED_SOURCE = """
        package com.example.payment;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Settlement arithmetic is audited - do not refactor")
        public class Settlement {}
        """;

    private static final String CONTEXT_SOURCE = """
        package com.example.search;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Ranking weights tuned by hand", avoids = "reflection")
        public class Ranker {}
        """;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness directoryOnlyProject(Path root) throws IOException {
        Files.createDirectories(root.resolve(".clinerules"));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.user.UserRecord", USER_SOURCE);
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);
        h.addSource("com.example.search.Ranker", CONTEXT_SOURCE);
        return h;
    }

    @Test
    void aDirectoryOnlyProjectGetsTheSafetyTierInAFileWithNoFrontMatter(@TempDir Path root)
            throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root);

        h.compile();

        assertTrue(h.fileExists(SAFETY_FILE),
            "a project whose only Cline output is the directory must still get an always-loaded "
                + "safety file");
        String safety = h.readFile(SAFETY_FILE);
        assertFalse(safety.startsWith("---"),
            "Cline activates a rule with front matter only when its conditions match, so the safety "
                + "file must carry none. Was:\n" + safety);
        assertTrue(safety.contains("Email is PII - never log it"),
            "the @AIPrivacy field must be always loaded, not only once UserRecord.java is in "
                + "context. Was:\n" + safety);
        assertTrue(safety.contains("Settlement arithmetic is audited - do not refactor"),
            "the @AILocked class must be always loaded. Was:\n" + safety);
        assertFalse(safety.contains("Ranking weights tuned by hand"),
            "@AIContext is the verbose tier and stays in its scoped rule file. Was:\n" + safety);
        assertTrue(h.fileExists(".clinerules/com-example-search-Ranker.md"),
            "the per-element rule files must still be written beside the safety file");
    }

    /**
     * The orphan sweep scrubs every VibeTags block in the directory whose stem this round did not
     * write as a per-element rule. The safety file is written by a different service, so without an
     * exclusion the sweep that runs after the write empties it in the same round.
     */
    @Test
    void theOrphanSweepLeavesTheSafetyFileAlone(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root);
        h.compile();
        VibeTagsLogger.shutdown();

        // A second round with a changed element set, so the fingerprint cannot skip it and the
        // sweep has a genuine orphan (the Ranker rule file) to remove.
        h.clearSources();
        h.addSource("com.example.user.UserRecord", USER_SOURCE);
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);
        h.compile();

        assertFalse(h.fileExists(".clinerules/com-example-search-Ranker.md"),
            "precondition: the sweep ran and removed the departed element's rule file");
        String safety = h.readFile(SAFETY_FILE);
        assertTrue(safety.contains("Email is PII - never log it")
                && safety.contains("Settlement arithmetic is audited - do not refactor"),
            "the sweep must not treat the safety file as an orphan. Was:\n" + safety);
    }

    @Test
    void handWrittenTextOutsideTheMarkersSurvives(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root);
        Path safetyFile = root.resolve(SAFETY_FILE);
        Files.writeString(safetyFile, "# Team rule\n\nRun the formatter before every change.\n",
            StandardCharsets.UTF_8);

        h.compile();

        String safety = Files.readString(safetyFile, StandardCharsets.UTF_8);
        assertTrue(safety.contains("Run the formatter before every change."),
            "text the user wrote in the safety file must survive the build (invariant 2). Was:\n"
                + safety);
        assertTrue(safety.contains("Email is PII - never log it"),
            "and the generated block must be added beside it. Was:\n" + safety);
    }

    /**
     * Removing the last safety-tier annotation must take its guardrail out of a file Cline loads on
     * every request. A stale "do not refactor Settlement" that outlives its annotation is a false
     * guardrail in front of the agent, which is why the file keeps being rendered when the tier is
     * empty rather than being left alone.
     */
    @Test
    void removingTheLastSafetyAnnotationRetiresItsGuardrail(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root);
        h.compile();
        assertTrue(h.readFile(SAFETY_FILE).contains("Settlement arithmetic is audited"),
            "precondition: the locked class reached the safety file");
        VibeTagsLogger.shutdown();

        h.clearSources();
        h.addSource("com.example.search.Ranker", CONTEXT_SOURCE);
        h.compile();

        String safety = h.readFile(SAFETY_FILE);
        assertFalse(safety.contains("Settlement arithmetic is audited")
                || safety.contains("Email is PII"),
            "a guardrail whose annotation is gone must leave the always-loaded file. Was:\n" + safety);
    }

    /**
     * The safety file shares a directory with per-element and role-grouped rule files, whose names
     * users influence. A name either could produce would let a rule file overwrite the safety file,
     * or the sweep exclusion protect a stale rule file, so the name uses a character neither can.
     */
    @Test
    void noElementOrRoleStemCanCollideWithTheSafetyFile(@TempDir Path root) throws IOException {
        String stem = ServiceRegistry.CLINE_SAFETY_FILE.substring(
            0, ServiceRegistry.CLINE_SAFETY_FILE.length() - ".md".length());
        assertFalse(stem.matches("[A-Za-z0-9-]+"),
            "ElementNaming.granularQName produces only [A-Za-z0-9-], so the stem must fall outside it");
        assertFalse(stem.matches("[A-Za-z0-9._-]+"),
            "RoleConfig.sanitize produces only [A-Za-z0-9._-], so the stem must fall outside it");
        assertNotEquals(stem, RoleConfig.sanitize(stem),
            "a role named after the safety file must sanitize to a different stem");

        ProcessorTestHarness h = directoryOnlyProject(root);
        Files.writeString(root.resolve(".vibetags-roles"), stem + " = **/search/**\n",
            StandardCharsets.UTF_8);

        h.compile();

        String safety = h.readFile(SAFETY_FILE);
        assertTrue(safety.contains("Email is PII - never log it"),
            "the safety file must still carry the safety tier. Was:\n" + safety);
        assertFalse(safety.contains("Ranking weights tuned by hand"),
            "a role named after the safety file must not write into it. Was:\n" + safety);
        String roleFile = ".clinerules/" + RoleConfig.sanitize(stem) + ".md";
        assertTrue(h.fileExists(roleFile),
            "the role must land in its own sanitized file beside the safety file: " + roleFile);
        assertTrue(h.readFile(roleFile).contains("Ranking weights tuned by hand"),
            "and that role file must carry the routed element");
    }

    /** The single {@code .clinerules} file already carries the safety tier inline; nothing is added. */
    @Test
    void theSingleFileFormGetsNoSafetyFile(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".clinerules"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);

        h.compile();

        assertTrue(Files.isRegularFile(root.resolve(".clinerules")),
            ".clinerules must stay a file");
        assertTrue(h.readFile(".clinerules").contains("Settlement arithmetic is audited"),
            "the single file carries the locked class inline, as before");
    }

    /**
     * A reactor with the directory at its shared root. Each module writes the safety file from its
     * own compile, so without a merge the module that compiled last would own every always-loaded
     * guardrail and the others would drop out of it, which is issue #365's failure in a new file.
     */
    @Test
    void everyModuleKeepsItsSafetyTierInTheSharedRootFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".clinerules"));
        compileModule(root, "module-users", "com.example.user.UserRecord", USER_SOURCE);
        compileModule(root, "module-payments", "com.example.payment.Settlement", LOCKED_SOURCE);

        String safety = Files.readString(root.resolve(SAFETY_FILE), StandardCharsets.UTF_8);
        assertTrue(safety.contains("Email is PII - never log it"),
            "the first module's safety tier must survive the second module's compile. Was:\n" + safety);
        assertTrue(safety.contains("Settlement arithmetic is audited - do not refactor"),
            "the second module's safety tier must be present. Was:\n" + safety);

        // `mvn -pl module-users compile`: recompiling one module must not drop its sibling.
        compileModule(root, "module-users", "com.example.user.UserRecord", USER_SOURCE);

        String afterOneModule = Files.readString(root.resolve(SAFETY_FILE), StandardCharsets.UTF_8);
        assertTrue(afterOneModule.contains("Settlement arithmetic is audited - do not refactor"),
            "a single-module recompile must keep the sibling's always-loaded guardrail. Was:\n"
                + afterOneModule);
    }

    private static void compileModule(Path root, String module, String fqn, String source)
            throws IOException {
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    @Test
    void checkModeAgreesWithGeneration(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root);
        h.compile();
        VibeTagsLogger.shutdown();

        List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> diagnostics =
            h.compileReturningDiagnostics("-Avibetags.check=true");

        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR),
            "check mode must reproduce the safety file exactly, or it reports drift a real build "
                + "would never produce: " + diagnostics);
    }
}
