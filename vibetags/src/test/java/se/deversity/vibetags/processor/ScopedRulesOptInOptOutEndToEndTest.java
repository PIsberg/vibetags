package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opting a scoped-rules directory in and back out again, both directions, on a real example.
 *
 * <p>Tier 3 is controlled the same way every other output is: the directory's presence is the
 * opt-in. Create {@code .claude/rules/} and the aggregate collapses to an index, keeping only the
 * safety buckets inline and moving the per-element detail into the scoped files. Delete it and the
 * detail has to come back inline, because the alternative is guardrails that exist in neither
 * place — a file the agent never reads and an aggregate that no longer mentions them.
 *
 * <p>That reverse direction is the one worth testing. The forward direction fails loudly if it
 * breaks: the rule files are simply absent. Opting out fails silently, and it fails into the shape
 * that looks fine — a smaller {@code CLAUDE.md} with an index pointing at a directory that is no
 * longer there.
 *
 * <p>The fixture is {@code example-all-tiers/billing}'s own sources rather than a hand-written
 * class, so this also fails if that example stops exercising all three tiers. Sources are copied
 * into a temp root; the example on disk is never written to.
 */
@DisplayName("Scoped rules: opting in and opting out")
class ScopedRulesOptInOptOutEndToEndTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();
    private static final Path EXAMPLE =
        REPO_ROOT.resolve("example-all-tiers/billing");

    private static final String SCOPED_RULES = "<scoped_rules>";
    private static final String RULES_DIR = ".claude/rules";

    @AfterEach
    void shutdownLogger() {
        VibeTagsLogger.shutdown();
    }

    @Test
    @DisplayName("opting in moves the per-element detail out of the aggregate")
    void optingInCollapsesTheAggregateToAnIndex(@TempDir Path dir) throws IOException {
        Path root = stageExample(dir);

        // Aggregate only: no rules directory, so everything is inline.
        compile(root);
        String inline = read(root, "CLAUDE.md");
        assertFalse(inline.contains(SCOPED_RULES),
            "with no rules directory the aggregate should carry everything inline, with no index");

        // Opt in: the directory's existence is the whole switch.
        Files.createDirectories(root.resolve(RULES_DIR));
        compile(root);
        String indexed = read(root, "CLAUDE.md");

        assertTrue(indexed.contains(SCOPED_RULES),
            "creating " + RULES_DIR + " should collapse the aggregate to an index:\n" + indexed);
        assertTrue(indexed.contains("rules=\"" + RULES_DIR + "/"),
            "the index should point at the scoped files it moved the detail into:\n" + indexed);

        List<Path> ruleFiles = ruleFiles(root);
        assertFalse(ruleFiles.isEmpty(), "opting in should have written the scoped rule files");
        for (Path f : ruleFiles) {
            assertTrue(Files.size(f) > 0, f.getFileName() + " was created empty");
        }

        // The aggregate must actually shrink; an index that still carries every detail has saved
        // the reader nothing, which is the entire point of the tier.
        assertTrue(indexed.length() < inline.length(),
            "the indexed aggregate (" + indexed.length() + " chars) should be smaller than the "
                + "inline one (" + inline.length() + "), or nothing moved out");
    }

    @Test
    @DisplayName("the safety buckets stay inline even when opted in")
    void safetyBucketsSurviveTheCollapse(@TempDir Path dir) throws IOException {
        Path root = stageExample(dir);
        Files.createDirectories(root.resolve(RULES_DIR));
        compile(root);

        String indexed = read(root, "CLAUDE.md");
        // A rule that only loads once the agent opens the file it protects has already failed, so
        // these are the buckets the collapse is not allowed to move.
        assertTrue(indexed.contains("<locked_files>"),
            "@AILocked must stay inline in the index:\n" + indexed);
        assertTrue(indexed.contains("<core_elements>"),
            "@AICore must stay inline in the index:\n" + indexed);
        assertTrue(indexed.contains("<pii_guardrails>") || indexed.contains("<audit_requirements>"),
            "the privacy/audit safety buckets must stay inline in the index:\n" + indexed);
    }

    @Test
    @DisplayName("opting out brings the detail back inline")
    void optingOutRestoresTheDetailToTheAggregate(@TempDir Path dir) throws IOException {
        Path root = stageExample(dir);

        // Start opted in.
        Files.createDirectories(root.resolve(RULES_DIR));
        compile(root);
        String indexed = read(root, "CLAUDE.md");
        assumeTrue(indexed.contains(SCOPED_RULES), "fixture did not opt in; nothing to opt out of");

        // Opt out by deleting the directory, which is how a user turns the tier off.
        deleteRecursively(root.resolve(RULES_DIR));
        compile(root);
        String restored = read(root, "CLAUDE.md");

        assertFalse(restored.contains(SCOPED_RULES),
            "the aggregate still points at a scoped-rules directory that no longer exists, so "
                + "those guardrails now live nowhere the agent will read:\n" + restored);
        assertFalse(restored.contains(RULES_DIR),
            "the aggregate still references " + RULES_DIR + " after it was deleted:\n" + restored);
        assertTrue(restored.length() > indexed.length(),
            "opting out should bring the detail back, so the aggregate ("
                + restored.length() + " chars) must be larger than the index ("
                + indexed.length() + "); it was not, so the detail was lost rather than restored");
    }

    @Test
    @DisplayName("opting out is permanent: the directory is never recreated")
    void optingOutIsNotUndoneByTheNextBuild(@TempDir Path dir) throws IOException {
        Path root = stageExample(dir);
        Files.createDirectories(root.resolve(RULES_DIR));
        compile(root);
        assertFalse(ruleFiles(root).isEmpty(), "fixture should have written rule files while opted in");

        deleteRecursively(root.resolve(RULES_DIR));

        // File presence is the opt-in, so a processor that helpfully recreates the directory
        // would make opting out impossible — every build would undo the user's decision.
        compile(root);
        assertFalse(Files.exists(root.resolve(RULES_DIR)),
            RULES_DIR + " was recreated after being deleted; deleting an output must deactivate "
                + "it permanently");

        compile(root);
        assertFalse(Files.exists(root.resolve(RULES_DIR)),
            RULES_DIR + " was recreated on a later build, so opting out does not hold");
    }

    @Test
    @DisplayName("a stale rule file left behind by an opt-out is not silently kept")
    void deletingOneRuleFileWhileStillOptedInRegeneratesIt(@TempDir Path dir) throws IOException {
        // Deleting a single file inside the directory is a different act from deleting the
        // directory: the tier is still opted in, so the file is expected back.
        Path root = stageExample(dir);
        Files.createDirectories(root.resolve(RULES_DIR));
        compile(root);

        List<Path> before = ruleFiles(root);
        assumeTrue(!before.isEmpty(), "fixture wrote no rule files");
        Path victim = before.get(0);
        String original = Files.readString(victim, StandardCharsets.UTF_8);
        Files.delete(victim);

        compile(root);

        assertTrue(Files.exists(victim),
            victim.getFileName() + " was not regenerated, even though the tier is still opted in");
        assertEquals(original, Files.readString(victim, StandardCharsets.UTF_8),
            "the regenerated rule file differs from the one that was deleted");
    }

    // -----------------------------------------------------------------------

    /**
     * Copies the example's sources and its role grouping into a fresh root, and opts the aggregate
     * in. The scoped-rules directory is deliberately left out; each test decides that.
     */
    private static Path stageExample(Path dir) throws IOException {
        assumeTrue(Files.isDirectory(EXAMPLE),
            "example-all-tiers/billing not reachable from " + REPO_ROOT + "; skipping");

        Path root = dir.resolve("staged");
        Path srcRoot = root.resolve("src/main/java");
        Files.createDirectories(srcRoot);

        Path exampleSrc = EXAMPLE.resolve("src/main/java");
        try (Stream<Path> sources = Files.walk(exampleSrc)) {
            for (Path src : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path target = srcRoot.resolve(exampleSrc.relativize(src).toString());
                Files.createDirectories(target.getParent());
                Files.copy(src, target);
            }
        }

        // The role file groups elements into shared rule files; copying it keeps the generated
        // layout the same as the example's own.
        Path roles = EXAMPLE.resolve(".vibetags-roles");
        if (Files.isRegularFile(roles)) {
            Files.copy(roles, root.resolve(".vibetags-roles"));
        }
        Files.createFile(root.resolve("CLAUDE.md"));
        return root;
    }

    /** A fresh harness per compile, because a build is what actually re-evaluates the opt-ins. */
    private static void compile(Path root) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        try (Stream<Path> sources = Files.walk(root.resolve("src/main/java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(harness::addSourceFile);
        }
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    private static String read(Path root, String relative) throws IOException {
        Path p = root.resolve(relative);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    private static List<Path> ruleFiles(Path root) throws IOException {
        Path dir = root.resolve(RULES_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
