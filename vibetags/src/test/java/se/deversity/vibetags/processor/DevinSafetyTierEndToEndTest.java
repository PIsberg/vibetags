package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.model.RoleConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The always-loaded safety tier for Devin Desktop's {@code .devin/rules/} and Windsurf's
 * {@code .windsurf/rules/} (issue #684), the same gap #648 closed for Cline.
 *
 * <p>Every per-element rule file in those directories carries {@code trigger: glob}, which
 * docs.devin.ai describes as "applied when Cascade reads or edits a file matching the globs
 * pattern". That is right for the verbose tier and wrong for the six safety buckets: invariant 6
 * keeps {@code @AILocked}, {@code @AICore}, {@code @AIPrivacy}, {@code @AIIgnore}, {@code @AIAudit}
 * and {@code @AISecure} always loaded, and a project on a rules directory alone had no file that
 * did. The same page documents {@code trigger: always_on}: "Full rule content is included in the
 * system prompt on every message."
 *
 * <p>{@code .windsurfrules} is read by Devin Desktop and Devin CLI beside both directories, and it
 * keeps the safety buckets inline, so when it is opted in the directory's safety file must not
 * repeat them. {@code .devin/rules/} and {@code .windsurf/rules/} are both loaded too, so with both
 * opted in only the preferred {@code .devin/rules/} one carries them.
 */
@Tag("e2e")
class DevinSafetyTierEndToEndTest {

    private static final String SAFETY_FILE = "+vibetags-safety.md";
    private static final String ALWAYS_ON = "---\ntrigger: always_on\n---\n";

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

    private static ProcessorTestHarness directoryOnlyProject(Path root, String dir) throws IOException {
        Files.createDirectories(root.resolve(dir));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.user.UserRecord", USER_SOURCE);
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);
        h.addSource("com.example.search.Ranker", CONTEXT_SOURCE);
        return h;
    }

    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void aDirectoryOnlyProjectGetsTheSafetyTierInAnAlwaysOnRule(String dir, @TempDir Path root)
            throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root, dir);

        h.compile();

        String file = dir + "/" + SAFETY_FILE;
        assertTrue(h.fileExists(file),
            "a project whose only Devin Desktop output is " + dir + "/ must still get an always-loaded "
                + "safety file");
        String safety = h.readFile(file);
        assertTrue(safety.startsWith(ALWAYS_ON + "\n"),
            "the vendor's always-on front matter, and nothing else, must open the file. Was:\n" + safety);
        assertEquals(1, count(safety, "trigger:"), "exactly one trigger. Was:\n" + safety);
        assertTrue(safety.contains("Email is PII - never log it"),
            "the @AIPrivacy field must be always loaded, not only once UserRecord.java is read. Was:\n"
                + safety);
        assertTrue(safety.contains("Settlement arithmetic is audited - do not refactor"),
            "the @AILocked class must be always loaded. Was:\n" + safety);
        assertFalse(safety.contains("Ranking weights tuned by hand"),
            "@AIContext is the verbose tier and stays in its glob rule file. Was:\n" + safety);
        String ranker = h.readFile(dir + "/com-example-search-Ranker.md");
        assertTrue(ranker.startsWith("---\ntrigger: glob\n") && ranker.contains("Ranking weights tuned by hand"),
            "the per-element rule files keep their glob trigger beside the safety file. Was:\n" + ranker);
    }

    /**
     * The orphan sweep scrubs every VibeTags block in the directory whose stem this round did not
     * write as a rule file. The safety file is written by another service, in the aggregate phase,
     * so without the exclusion the sweep empties it in the same round.
     */
    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void theOrphanSweepLeavesTheSafetyFileAlone(String dir, @TempDir Path root) throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root, dir);
        h.compile();
        VibeTagsLogger.shutdown();

        h.clearSources();
        h.addSource("com.example.user.UserRecord", USER_SOURCE);
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);
        h.compile();

        assertFalse(h.fileExists(dir + "/com-example-search-Ranker.md"),
            "precondition: the sweep ran and removed the departed element's rule file");
        String safety = h.readFile(dir + "/" + SAFETY_FILE);
        assertTrue(safety.contains("Email is PII - never log it")
                && safety.contains("Settlement arithmetic is audited - do not refactor"),
            "the sweep must not treat the safety file as an orphan. Was:\n" + safety);
    }

    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void handWrittenTextOutsideTheMarkersSurvivesBelowTheFrontMatter(String dir, @TempDir Path root)
            throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root, dir);
        Path safetyFile = root.resolve(dir).resolve(SAFETY_FILE);
        Files.writeString(safetyFile, "# Team rule\n\nRun the formatter before every change.\n",
            StandardCharsets.UTF_8);

        h.compile();
        VibeTagsLogger.shutdown();
        h.compile();

        String safety = Files.readString(safetyFile, StandardCharsets.UTF_8);
        assertTrue(safety.startsWith(ALWAYS_ON),
            "a rule without the trigger has no documented activation, so the header goes on top. Was:\n"
                + safety);
        assertEquals(1, count(safety, "trigger:"), "a rebuild must not stack a second header:\n" + safety);
        assertTrue(safety.contains("Run the formatter before every change."),
            "text the user wrote in the safety file must survive the build (invariant 2). Was:\n" + safety);
        assertTrue(safety.contains("Email is PII - never log it"),
            "and the generated block must be added beside it. Was:\n" + safety);
    }

    /**
     * Removing the last safety-tier annotation must take its guardrail out of a file loaded on every
     * message, which is why the file keeps being rendered when the tier is empty.
     */
    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void removingTheLastSafetyAnnotationRetiresItsGuardrail(String dir, @TempDir Path root)
            throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root, dir);
        h.compile();
        assertTrue(h.readFile(dir + "/" + SAFETY_FILE).contains("Settlement arithmetic is audited"),
            "precondition: the locked class reached the safety file");
        VibeTagsLogger.shutdown();

        h.clearSources();
        h.addSource("com.example.search.Ranker", CONTEXT_SOURCE);
        h.compile();

        String safety = h.readFile(dir + "/" + SAFETY_FILE);
        assertTrue(safety.startsWith(ALWAYS_ON), safety);
        assertFalse(safety.contains("Settlement arithmetic is audited") || safety.contains("Email is PII"),
            "a guardrail whose annotation is gone must leave the always-loaded file. Was:\n" + safety);
    }

    /** Element and role stems cannot produce the name, so a role named after it lands elsewhere. */
    @Test
    void aRoleNamedAfterTheSafetyFileCannotWriteIntoIt(@TempDir Path root) throws IOException {
        String stem = SAFETY_FILE.substring(0, SAFETY_FILE.length() - ".md".length());
        ProcessorTestHarness h = directoryOnlyProject(root, ".devin/rules");
        Files.writeString(root.resolve(".vibetags-roles"), stem + " = **/search/**\n", StandardCharsets.UTF_8);

        h.compile();

        String safety = h.readFile(".devin/rules/" + SAFETY_FILE);
        assertTrue(safety.startsWith(ALWAYS_ON) && safety.contains("Email is PII - never log it"), safety);
        assertFalse(safety.contains("Ranking weights tuned by hand"),
            "a role named after the safety file must not write into it. Was:\n" + safety);
        String roleFile = ".devin/rules/" + RoleConfig.sanitize(stem) + ".md";
        assertTrue(h.readFile(roleFile).contains("Ranking weights tuned by hand"),
            "the role lands in its own sanitized file: " + roleFile);
    }

    /**
     * Devin Desktop reads {@code .windsurfrules} beside both directories ("The legacy single-file
     * .windsurfrules at the workspace root is also still read"), and Devin CLI loads it "as
     * always-on rules". It keeps the safety buckets inline, so repeating them in a directory's
     * safety file would put every one in the system prompt twice.
     */
    @Test
    void withWindsurfrulesOptedInTheSafetyTierIsLoadedFromItAlone(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.createDirectories(root.resolve(".devin/rules"));
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn(".windsurfrules");
        h.addSource("com.example.user.UserRecord", USER_SOURCE);
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);

        h.compile();

        String aggregate = h.readFile(".windsurfrules");
        assertTrue(aggregate.contains("Email is PII - never log it")
                && aggregate.contains("Settlement arithmetic is audited - do not refactor"),
            "precondition: .windsurfrules carries the safety tier inline. Was:\n" + aggregate);
        for (String dir : List.of(".devin/rules", ".windsurf/rules")) {
            String safety = h.readFile(dir + "/" + SAFETY_FILE);
            assertTrue(safety.startsWith(ALWAYS_ON), safety);
            assertFalse(safety.contains("Email is PII") || safety.contains("Settlement arithmetic"),
                dir + " must not repeat what .windsurfrules already loads on every message. Was:\n" + safety);
            assertTrue(safety.contains(".windsurfrules"),
                dir + " says where the safety tier is instead. Was:\n" + safety);
        }
    }

    /**
     * Devin CLI: "Rule files in .devin/rules/ and .windsurf/rules/ are both loaded", and Devin
     * Desktop prefers {@code .devin/}. With both opted in, the preferred directory carries the tier.
     */
    @Test
    void withBothDirectoriesTheDevinDirectoryCarriesTheSafetyTier(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".windsurf/rules"));
        ProcessorTestHarness h = directoryOnlyProject(root, ".devin/rules");

        h.compile();

        String devin = h.readFile(".devin/rules/" + SAFETY_FILE);
        assertTrue(devin.startsWith(ALWAYS_ON) && devin.contains("Email is PII - never log it"), devin);
        String windsurf = h.readFile(".windsurf/rules/" + SAFETY_FILE);
        assertTrue(windsurf.startsWith(ALWAYS_ON), windsurf);
        assertFalse(windsurf.contains("Email is PII"),
            "the fallback directory must not load the tier a second time. Was:\n" + windsurf);
        assertTrue(windsurf.contains(".devin/rules/"), "it names where the tier is. Was:\n" + windsurf);
    }

    /** No directory, no file: the aggregate alone must not create one (invariant 1). */
    @Test
    void theAggregateAloneCreatesNoRulesDirectory(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn(".windsurfrules");
        h.addSource("com.example.payment.Settlement", LOCKED_SOURCE);

        h.compile();

        assertTrue(h.readFile(".windsurfrules").contains("Settlement arithmetic is audited"));
        assertFalse(Files.exists(root.resolve(".windsurf")), "no .windsurf/ without the opt-in");
        assertFalse(Files.exists(root.resolve(".devin")), "no .devin/ without the opt-in");
    }

    /**
     * A reactor with the directory at its shared root. Each module renders the file with its own
     * front matter, and a merge that stacked whole renderings would bury every module's trigger
     * inside a sub-marker, leaving a file that opens with no trigger at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void everyModuleKeepsItsSafetyTierInTheSharedRootFile(String dir, @TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(dir));
        compileModule(root, "module-users", "com.example.user.UserRecord", USER_SOURCE);
        compileModule(root, "module-payments", "com.example.payment.Settlement", LOCKED_SOURCE);

        Path file = root.resolve(dir).resolve(SAFETY_FILE);
        String safety = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(safety.startsWith(ALWAYS_ON),
            "the merged file must open with the trigger, written once. Was:\n" + safety);
        assertEquals(1, count(safety, "trigger:"), "one trigger for the whole file. Was:\n" + safety);
        assertTrue(safety.contains("Email is PII - never log it"),
            "the first module's safety tier must survive the second module's compile. Was:\n" + safety);
        assertTrue(safety.contains("Settlement arithmetic is audited - do not refactor"),
            "the second module's safety tier must be present. Was:\n" + safety);

        compileModule(root, "module-users", "com.example.user.UserRecord", USER_SOURCE);

        String afterOneModule = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(afterOneModule.startsWith(ALWAYS_ON) && count(afterOneModule, "trigger:") == 1,
            afterOneModule);
        assertTrue(afterOneModule.contains("Settlement arithmetic is audited - do not refactor"),
            "a single-module recompile must keep the sibling's always-loaded guardrail. Was:\n"
                + afterOneModule);
    }

    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void checkModeAgreesWithGeneration(String dir, @TempDir Path root) throws IOException {
        ProcessorTestHarness h = directoryOnlyProject(root, dir);
        h.compile();
        VibeTagsLogger.shutdown();

        List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> diagnostics =
            h.compileReturningDiagnostics("-Avibetags.check=true");

        assertTrue(h.fileExists(dir + "/" + SAFETY_FILE), "precondition: the safety file was written");
        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR),
            "check mode must reproduce the safety file exactly: " + diagnostics);
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

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
