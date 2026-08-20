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
 * Regression tests for a Gradle repository with exactly one included subproject whose directory is
 * a strict subdirectory of the VibeTags root.
 *
 * <p>Reported layout: {@code settings.gradle} at the git root declares {@code include 'webapp'},
 * all Java sources live under {@code webapp/}, and the subproject's {@code compileJava} passes
 * {@code -Avibetags.root} pointing at the git root, one level above the sources. Two sidecars were
 * on disk for what is really one module:
 *
 * <pre>
 *   .vibetags-mod-_root_       moduleId=_root_      modulePath=
 *   .vibetags-mod-webapp   moduleId=webapp  modulePath=webapp
 * </pre>
 *
 * <p>Both carried byte-identical bodies for every annotated element, because both were rendered
 * from the same source tree: the {@code _root_} one from a build whose module identity resolved to
 * the ancestor directory, the {@code webapp} one from a build that resolved the subproject.
 * The stale check in {@code ModuleSidecar.readAll} exempts an empty {@code modulePath} - the root
 * directory always exists - so the ancestor sidecar was immortal, and every subsequent build
 * emitted its region beside the real module's. The result was two {@code VIBETAGS-MODULE} regions
 * with word-for-word the same content in all 24 generated rule files.
 *
 * <p>An annotated element belongs to exactly one module. When an ancestor region's element set is
 * fully covered by the regions of modules nested inside it, that ancestor region is the same
 * sources counted twice under a less specific identity, and the most specific module must win.
 *
 * <p>Here the two identities are produced the way the report's repository reached them: the first
 * build sees no {@code build.gradle} in the subproject, so the module-root walk climbs past it to
 * the git root; the second build sees the subproject's own build file and resolves it.
 */
@Tag("e2e")
class AncestorModuleDuplicateRegionTest {

    private static final String AUTH_UTIL_SOURCE = """
        package com.example.auth;

        import se.deversity.vibetags.annotations.AISecure;

        @AISecure(aspect = "authentication")
        public class AuthUtil {
        }
        """;

    private static final String SIBLING_SOURCE = """
        package com.example.report;

        import se.deversity.vibetags.annotations.AICore;

        @AICore(sensitivity = "high", note = "Report totals are reconciled against the ledger")
        public class ReportBuilder {
        }
        """;

    private static final String ROOT_ONLY_SOURCE = """
        package com.example.root;

        import se.deversity.vibetags.annotations.AICore;

        @AICore(sensitivity = "high", note = "Root build logic")
        public class RootOnly {
        }
        """;

    @TempDir
    Path repoRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** The git root: settings.gradle declaring one subproject, plus a root build file. */
    private void setUpRepo() throws IOException {
        Files.writeString(repoRoot.resolve("settings.gradle"),
            "rootProject.name = 'webapp'\ninclude 'webapp'\n", StandardCharsets.UTF_8);
        Files.writeString(repoRoot.resolve("build.gradle"),
            "plugins { id 'java' }\n", StandardCharsets.UTF_8);
        Files.createDirectories(repoRoot.resolve(".gemini/rules"));
    }

    /** Gives the subproject its own build file, so the module-root walk stops there. */
    private void giveSubprojectItsOwnBuildFile() throws IOException {
        Files.createDirectories(repoRoot.resolve("webapp"));
        Files.writeString(repoRoot.resolve("webapp/build.gradle"),
            "plugins { id 'java' }\n", StandardCharsets.UTF_8);
    }

    /** One {@code :webapp:compileJava} pass, with {@code vibetags.root} at the git root. */
    private void compileSubproject() throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(repoRoot, false);
        harness.writeSourceFile("webapp/src/main/java/com/example/auth/AuthUtil.java",
            AUTH_UTIL_SOURCE);
        harness.writeSourceFile("webapp/src/main/java/com/example/report/ReportBuilder.java",
            SIBLING_SOURCE);
        harness.compile();
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
    void subprojectRegionReplacesTheAncestorRegionItSupersedes() throws IOException {
        setUpRepo();
        compileSubproject();                 // resolves to the git root  -> _root_
        giveSubprojectItsOwnBuildFile();
        compileSubproject();                 // resolves to the subproject -> webapp

        String authUtil = ruleFile("com-example-auth-AuthUtil");
        assertEquals(1, countOf(authUtil, "## Security-Critical Code"),
            "the guardrail must appear once, not once per module identity:\n" + authUtil);
        assertFalse(authUtil.contains("VIBETAGS-MODULE"),
            "one module leaves one region, so no sub-markers belong in the file:\n" + authUtil);

        String reportBuilder = ruleFile("com-example-report-ReportBuilder");
        assertEquals(1, countOf(reportBuilder, "Report totals are reconciled against the ledger"),
            "the guardrail must appear once, not once per module identity:\n" + reportBuilder);
    }

    @Test
    void supersededAncestorSidecarIsRemovedFromDisk() throws IOException {
        setUpRepo();
        compileSubproject();
        assertTrue(Files.exists(repoRoot.resolve(".vibetags-mod-_root_")),
            "precondition: the first build resolves the ancestor directory as the module");

        giveSubprojectItsOwnBuildFile();
        compileSubproject();

        assertTrue(Files.exists(repoRoot.resolve(".vibetags-mod-webapp")),
            "the real module's sidecar must stay");
        assertFalse(Files.exists(repoRoot.resolve(".vibetags-mod-_root_")),
            "the ancestor sidecar covers no element the subproject does not, so it must be pruned");
    }

    @Test
    void aggregateFileCarriesTheModuleContentOnlyOnce() throws IOException {
        setUpRepo();
        Files.createFile(repoRoot.resolve("GEMINI.md"));
        compileSubproject();
        giveSubprojectItsOwnBuildFile();
        compileSubproject();

        String aggregate = Files.readString(repoRoot.resolve("GEMINI.md"), StandardCharsets.UTF_8);
        assertFalse(aggregate.contains("VIBETAGS-MODULE"),
            "one module leaves one region, so no sub-markers belong in the aggregate: " + aggregate);
        assertEquals(1, countOf(aggregate, "Report totals are reconciled against the ledger"),
            "the aggregate must not repeat the module under two identities:\n" + aggregate);
    }

    /**
     * The ancestor region is superseded only when the nested modules account for every element it
     * holds. A root module with sources of its own keeps its region, sub-markers and all.
     */
    @Test
    void rootModuleWithItsOwnSourcesKeepsItsRegion() throws IOException {
        setUpRepo();
        giveSubprojectItsOwnBuildFile();

        ProcessorTestHarness rootPass = new ProcessorTestHarness(repoRoot, false);
        rootPass.writeSourceFile("src/main/java/com/example/root/RootOnly.java", ROOT_ONLY_SOURCE);
        rootPass.compile();

        compileSubproject();

        assertTrue(Files.exists(repoRoot.resolve(".vibetags-mod-_root_")),
            "a root module with an element no nested module has must keep its sidecar");
        String rootOnly = ruleFile("com-example-root-RootOnly");
        assertEquals(1, countOf(rootOnly, "Root build logic"),
            "the root module's own guardrails must survive:\n" + rootOnly);
        String authUtil = ruleFile("com-example-auth-AuthUtil");
        assertEquals(1, countOf(authUtil, "## Security-Critical Code"),
            "the subproject's guardrails must survive:\n" + authUtil);
    }
}
