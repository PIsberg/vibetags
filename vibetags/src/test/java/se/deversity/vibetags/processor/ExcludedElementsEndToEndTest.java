package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Avibetags.exclude} keeps an annotated element out of the generated files without making
 * the round look incomplete.
 *
 * <p>The distinction is the whole feature. Leaving a source out of the compilation with
 * {@code <testExcludes>} already "worked", in the sense that its annotations stopped appearing, but
 * {@link se.deversity.vibetags.processor.internal.PartialRoundDetector} then saw a round that had
 * not been shown every annotated source in its module and refused to write anything at all, which
 * is invariant 17 doing its job (<a href="https://github.com/PIsberg/vibetags/issues/792">#792</a>).
 * An excluded element must instead be <em>collected</em> and then not <em>published</em>: the
 * ledger stays satisfied, every other element in the round is written as usual, and only the
 * excluded one is absent.
 *
 * <p>The case that motivated it: this repository's own annotation-definition fixtures. They carry
 * one element per {@code @AI} type with placeholder reasons, purely to prove each annotation
 * compiles, and self-annotating the test sources published them as though they were real
 * guardrails, taking 6 638 bytes of {@code CLAUDE.md} to tell an agent never to edit a fixture
 * called {@code TestLockedClass}.
 */
@Tag("e2e")
@DisplayName("An excluded element is collected but not published")
class ExcludedElementsEndToEndTest {

    private static final String KEPT_REASON = "Ledger totals are integer minor units";
    private static final String EXCLUDED_REASON = "Do not modify this fixture under any circumstances";

    private static final String KEPT_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Ledger totals are integer minor units")
        public class Ledger {
        }
        """;

    private static final String FIXTURE_SOURCE = """
        package com.example.ledger.fixtures;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Do not modify this fixture under any circumstances")
        public class DefinitionFixture {
        }
        """;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private void compile(String... extraOptions) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/ledger/Ledger.java", KEPT_SOURCE);
        harness.writeSourceFile(
            "src/main/java/com/example/ledger/fixtures/DefinitionFixture.java", FIXTURE_SOURCE);
        harness.compile(extraOptions);
    }

    private String claude() throws IOException {
        return Files.readString(root.resolve("CLAUDE.md"));
    }

    /** Without the option nothing changes: both elements are published, as they always were. */
    @Test
    @DisplayName("by default every annotated element is still published")
    void withoutTheOptionBothElementsAreWritten() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        compile();

        String claude = claude();
        assertTrue(claude.contains(KEPT_REASON) && claude.contains(EXCLUDED_REASON),
            "the option is opt-in; with none given the output must be exactly what it was:\n" + claude);
    }

    /**
     * The heart of it. The excluded element is gone and the other one is still there, which is what
     * separates this from excluding the source: the round wrote, rather than refusing to.
     */
    @Test
    @DisplayName("the excluded element is absent and the rest of the round is written")
    void anExcludedElementIsNotPublishedButTheRoundStillWrites() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        compile("-Avibetags.exclude=com.example.ledger.fixtures.*");

        String claude = claude();
        assertFalse(claude.contains(EXCLUDED_REASON),
            "the excluded element must not reach the generated file:\n" + claude);
        assertTrue(claude.contains(KEPT_REASON),
            "and the round must still have written everything else. An empty or unchanged file "
                + "here means the round was refused as partial, which is the failure this option "
                + "exists to avoid:\n" + claude);
    }

    /**
     * Invariant 17, stated as a test rather than trusted. Excluding an element must not put the
     * build into the partial-round refusal, whose symptom is a file that never gets written at all.
     */
    @Test
    @DisplayName("excluding an element does not trip the partial-round guard")
    void excludingAnElementDoesNotReportAPartialRound() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/ledger/Ledger.java", KEPT_SOURCE);
        harness.writeSourceFile(
            "src/main/java/com/example/ledger/fixtures/DefinitionFixture.java", FIXTURE_SOURCE);
        List<String> messages = harness.compileReturningDiagnostics(
                "-Avibetags.exclude=com.example.ledger.fixtures.*").stream()
            .map(d -> d.getMessage(null))
            .toList();

        assertTrue(messages.stream().noneMatch(m -> m.contains("partial")),
            "an excluded element is collected, so the source ledger has seen it and the round is "
                + "complete. Diagnostics were:\n  " + String.join("\n  ", messages));
        assertTrue(claude().contains(KEPT_REASON), claude());
    }

    /** Invariant 12: the option is an input to the output, so toggling it must regenerate. */
    @Test
    @DisplayName("toggling the option regenerates rather than hitting the write cache")
    void togglingTheOptionRegenerates() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        compile();
        String published = claude();
        compile("-Avibetags.exclude=com.example.ledger.fixtures.*");
        String excluded = claude();

        assertNotEquals(published, excluded,
            "the second build changed no source, so only the fingerprint can have noticed the "
                + "option. Identical content means the write cache short-circuited it and the "
                + "exclusion never reached BuildFingerprint (invariant 12).");
        assertFalse(excluded.contains(EXCLUDED_REASON), excluded);

        // And back again, so the option is not a one-way door.
        compile();
        assertTrue(claude().contains(EXCLUDED_REASON),
            "removing the option must bring the element back:\n" + claude());
    }

    /** An excluded element owns no granular rule file either, which is where most of the bulk was. */
    @Test
    @DisplayName("no granular rule file is written for an excluded element")
    void anExcludedElementGetsNoGranularRuleFile() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude").resolve("rules"));

        compile("-Avibetags.exclude=com.example.ledger.fixtures.*");

        List<String> ruleFiles = ruleFileNames();
        assertTrue(ruleFiles.stream().noneMatch(n -> n.contains("DefinitionFixture")),
            "the excluded element must own no scoped rule file: " + ruleFiles);
        assertTrue(ruleFiles.stream().anyMatch(n -> n.contains("Ledger")),
            "and the element that was kept must still have one: " + ruleFiles);
    }

    /** Several patterns, comma-separated, and a wildcard that matches part of a name. */
    @Test
    @DisplayName("patterns are comma-separated globs over the fully-qualified name")
    void severalPatternsCanBeGivenAtOnce() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        compile("-Avibetags.exclude=com.example.nothing.*,*.DefinitionFixture");

        assertFalse(claude().contains(EXCLUDED_REASON),
            "the second pattern matches by wildcard, and one of several must be enough:\n" + claude());
        assertTrue(claude().contains(KEPT_REASON), claude());
    }

    /**
     * A pattern naming a class takes its members with it.
     *
     * <p>The case the first implementation got wrong, and the one the fixtures are actually made
     * of. Matching was on {@link se.deversity.vibetags.processor.model.TaggedElement#qualifiedName()},
     * which is documented as the element's own name "without any enclosing-type prefix": for an
     * annotated method that is the bare method name, so a pattern naming the enclosing class could
     * not match it. Excluding the fixture classes took out the nested types and left every
     * annotated method behind, and the earlier cases here all used top-level classes, where the
     * path and the qualified name are the same string and the bug is invisible.
     */
    @Test
    @DisplayName("excluding a class also excludes its nested types and annotated methods")
    void aClassPatternTakesItsMembersWithIt() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/ledger/Ledger.java", KEPT_SOURCE);
        harness.writeSourceFile("src/main/java/com/example/ledger/fixtures/Fixtures.java", """
            package com.example.ledger.fixtures;

            import se.deversity.vibetags.annotations.AILocked;

            public class Fixtures {

                @AILocked(reason = "Fixture method proving the annotation applies to methods")
                public void annotatedMethod() {
                }

                @AILocked(reason = "Fixture nested type proving the annotation applies to types")
                public static class NestedFixture {
                }
            }
            """);
        harness.compile("-Avibetags.exclude=com.example.ledger.fixtures.Fixtures*");

        String claude = claude();
        assertFalse(claude.contains("Fixture method proving"),
            "the annotated method must go with its enclosing class. Matching the element's own "
                + "qualified name instead of its path leaves methods behind:\n" + claude);
        assertFalse(claude.contains("Fixture nested type proving"),
            "and so must the nested type:\n" + claude);
        assertTrue(claude.contains(KEPT_REASON), claude);
    }

    /** A pattern matching nothing is not an error, and changes nothing. */
    @Test
    @DisplayName("a pattern that matches nothing leaves the output alone")
    void aPatternThatMatchesNothingChangesNothing() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));

        compile();
        String published = claude();
        compile("-Avibetags.exclude=com.example.absent.*");

        assertEquals(published, claude(),
            "no element matched, so the published set is identical and so is the file");
    }

    private List<String> ruleFileNames() throws IOException {
        Path dir = root.resolve(".claude").resolve("rules");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> String.valueOf(p.getFileName())).sorted().toList();
        }
    }
}
