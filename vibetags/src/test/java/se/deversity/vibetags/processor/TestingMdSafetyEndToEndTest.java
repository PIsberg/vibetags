package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six safety annotations never move to {@code TESTING.md}, whatever code they are on.
 *
 * <p>They are the guardrails an agent has to see without opening the annotated file: a locked
 * fixture shared with a partner, PII in a test data builder, a test helper that must not be read
 * at all. {@code TESTING.md} is loaded on demand, so a safety guardrail routed there would be one
 * an agent meets only after deciding to work on tests, which is after it mattered. Each case
 * builds the same project twice, with and without {@code TESTING.md}, and compares.
 */
@Tag("e2e")
class TestingMdSafetyEndToEndTest {

    private static final String MAIN_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Ledger totals are integer minor units")
        public class Ledger {
        }
        """;

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    static Stream<Arguments> safetyAnnotations() {
        return Stream.of(
            Arguments.of("AILocked", "@AILocked(reason = \"SAFETY-TEXT golden fixture is shared with a partner\")"),
            Arguments.of("AICore", "@AICore(sensitivity = \"high\", note = \"SAFETY-TEXT harness every suite depends on\")"),
            Arguments.of("AIPrivacy", "@AIPrivacy(reason = \"SAFETY-TEXT builder holds real-shaped PII\")"),
            Arguments.of("AIIgnore", "@AIIgnore(reason = \"SAFETY-TEXT recorded partner payloads\")"),
            Arguments.of("AIAudit", "@AIAudit(checkFor = {\"SAFETY-TEXT secrets in fixtures\"})"),
            Arguments.of("AISecure", "@AISecure(aspect = \"SAFETY-TEXT test tokens must never be production-shaped\")"));
    }

    private static String testSource(String annotation, String usage, String extraImport, String extraUsage) {
        return "package com.example.ledger;\n\n"
            + "import se.deversity.vibetags.annotations." + annotation + ";\n"
            + extraImport
            + "\n" + usage + "\n" + extraUsage
            + "public class GoldenLedgerFixture {\n}\n";
    }

    /** Builds main then tests under {@code root}, with or without the TESTING.md opt-in. */
    private Path build(String name, boolean withTestingMd, String testSource) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve(".cursorrules"));
        if (withTestingMd) {
            Files.createFile(root.resolve("TESTING.md"));
        }
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        for (String[] set : List.of(
                new String[]{"main", "Ledger", MAIN_SOURCE},
                new String[]{"test", "GoldenLedgerFixture", testSource})) {
            ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
            harness.writeSourceFile("src/" + set[0] + "/java/com/example/ledger/" + set[1] + ".java", set[2]);
            harness.compile();
            VibeTagsLogger.shutdown();
        }
        return root;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("safetyAnnotations")
    void aSafetyGuardrailOnTestCodeIsRenderedExactlyAsItWasBeforeTestingMdExisted(
            String annotation, String usage) throws IOException {
        String source = testSource(annotation, usage, "", "");
        Path with = build("with", true, source);
        Path without = build("without", false, source);

        String claude = Files.readString(with.resolve("CLAUDE.md"));
        assertTrue(claude.contains("SAFETY-TEXT"), "the guardrail must be in CLAUDE.md at all:\n" + claude);
        assertEquals(Files.readString(without.resolve("CLAUDE.md")), claude,
            "CLAUDE.md must not depend on TESTING.md when the test code carries only safety annotations");
        assertEquals(Files.readString(without.resolve(".cursorrules")),
            Files.readString(with.resolve(".cursorrules")), ".cursorrules likewise");

        String testing = Files.readString(with.resolve("TESTING.md"));
        assertFalse(testing.contains("SAFETY-TEXT"), "a safety guardrail must not be copied to TESTING.md:\n" + testing);
        assertFalse(testing.contains("GoldenLedgerFixture"),
            "a class with only safety annotations has no entry in TESTING.md:\n" + testing);
    }

    /** One class, both kinds: each file gets its own kind and only that. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("safetyAnnotations")
    void aClassCarryingBothKindsAppearsInBothFilesWithOnlyItsOwnKindInEach(
            String annotation, String usage) throws IOException {
        String source = testSource(annotation, usage,
            "import se.deversity.vibetags.annotations.AIContext;\n",
            "@AIContext(focus = \"ADVISORY-TEXT build ledgers through LedgerBuilder only\")\n");
        Path root = build("both", true, source);

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        String testing = Files.readString(root.resolve("TESTING.md"));
        assertTrue(claude.contains("SAFETY-TEXT") && !claude.contains("ADVISORY-TEXT"), "CLAUDE.md:\n" + claude);
        assertTrue(testing.contains("ADVISORY-TEXT") && !testing.contains("SAFETY-TEXT"), "TESTING.md:\n" + testing);
    }

    /** An ignore file is not an instruction file: an {@code @AIIgnore} on test code still excludes it. */
    @Test
    void anIgnoredTestFileIsStillExcludedWithTestingMdPresent() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ignore"));
        Files.createFile(root.resolve(".cursorignore"));
        Files.createFile(root.resolve(".claudeignore"));
        Path built = build("ignore", true,
            testSource("AIIgnore", "@AIIgnore(reason = \"recorded partner payloads\")", "", ""));

        for (String ignoreFile : List.of(".cursorignore", ".claudeignore")) {
            String content = Files.readString(built.resolve(ignoreFile));
            assertTrue(content.contains("GoldenLedgerFixture"), ignoreFile + " must still list the test file:\n" + content);
        }
    }
}
