package se.deversity.vibetags.ksp.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Kotlin test source set routes to {@code TESTING.md} under KSP, the same as a Java one does.
 *
 * <p>That this works at all rests on one fact: {@code KspElements.getFileObjectOf} hands
 * {@code ModuleRootResolver} the source file's real path, so the resolver reads
 * {@code src/test/kotlin} and classifies the round exactly as it classifies
 * {@code src/test/java}. That fact was established by reading the code when routing was designed,
 * and nothing ran it. A front end that reported the wrong source set would send every Kotlin test
 * guardrail into the always-loaded files with no warning anywhere, and the Java suite would stay
 * green throughout.
 *
 * <p>Both halves are asserted from one pair of rounds, because the failure that matters is not
 * "nothing moved" but "the wrong thing moved": a safety annotation reaching {@code TESTING.md} is
 * worse than no routing at all.
 */
class KspTestingMdRoutingTest {

    private static final String IMPORTS = "import se.deversity.vibetags.annotations.*\n\n";

    @TempDir
    Path root;

    private Path sourceSet(String name) throws IOException {
        return Files.createDirectories(root.resolve("src/" + name + "/kotlin"));
    }

    /** Writes one file into {@code src/<sourceSet>/kotlin} and returns that source root. */
    private Path write(String sourceSet, String relative, String content) throws IOException {
        Path sources = sourceSet(sourceSet);
        Path file = sources.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return sources;
    }

    /** The build file is what makes this a module root, which is what gives a round a source set. */
    private void optIn(String... files) throws IOException {
        Files.writeString(root.resolve("build.gradle.kts"), "");
        for (String file : files) {
            Files.writeString(root.resolve(file), "");
        }
    }

    @Test
    void aKotlinTestSourceSetRoutesItsAdvisoryGuardrailsAndKeepsItsSafetyOnes() throws IOException {
        optIn("CLAUDE.md", "TESTING.md");

        Path main = write("main", "com/a/Ledger.kt", "package com.a\n" + IMPORTS
            + "@AIContext(focus = \"LEDGER-MAIN totals are integer minor units\") class Ledger\n");
        KspHarness.Result mainRound = new KspHarness(main, root).run();
        assertEquals("OK", mainRound.exitCode(), () -> String.valueOf(mainRound.errors()));

        assertFalse(Files.readString(root.resolve("TESTING.md")).contains("LEDGER-MAIN"),
            "a main round has nothing to say about test code:\n"
                + Files.readString(root.resolve("TESTING.md")));

        Path tests = write("test", "com/a/LedgerFixtures.kt", "package com.a\n" + IMPORTS
            + "@AIContext(focus = \"FIXTURE-ADVICE build ledgers through LedgerBuilder only\")\n"
            + "@AILocked(reason = \"FIXTURE-LOCK golden payloads are the partner's recorded responses\")\n"
            + "class LedgerFixtures\n");
        KspHarness.Result testRound = new KspHarness(tests, root).run();
        assertEquals("OK", testRound.exitCode(), () -> String.valueOf(testRound.errors()));

        String testing = Files.readString(root.resolve("TESTING.md"));
        String claude = Files.readString(root.resolve("CLAUDE.md"));

        assertTrue(testing.contains("FIXTURE-ADVICE"),
            "the Kotlin test class's advisory guardrail must reach TESTING.md, which it only can if "
                + "the KSP front end reported src/test/kotlin as a test source set:\n" + testing);
        assertFalse(claude.contains("FIXTURE-ADVICE"),
            "and must leave the always-loaded file:\n" + claude);

        assertTrue(claude.contains("FIXTURE-LOCK"),
            "the safety-tier guardrail stays where an agent meets it without opening the file:\n" + claude);
        assertFalse(testing.contains("FIXTURE-LOCK"),
            "and must not be copied into the on-demand file:\n" + testing);

        assertTrue(claude.contains("LEDGER-MAIN"),
            "the main round's guardrail is untouched by any of this:\n" + claude);
    }
}
