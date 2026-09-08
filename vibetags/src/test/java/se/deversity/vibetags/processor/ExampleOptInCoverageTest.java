package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code examples/basic} is the exhaustive fixture: it opts into every platform, so a change to any
 * renderer shows up in its committed output and CI's byte-for-byte drift gate catches it. The other
 * examples deliberately carry subsets, each exercising something specific
 * ({@code examples/INDEX.md} is the ledger).
 *
 * <p>"Exhaustive" was a convention, not a checked property, and it had already lapsed.
 * {@code .gemini/rules} shipped in #320 and no example ever opted into it, so for four releases
 * Gemini's granular output had no committed fixture, no drift gate, and no coverage of the
 * dual-opt-in path where {@code GEMINI.md} collapses to a scoped-rules index. Nothing failed,
 * because nothing was looking.
 *
 * <p>A new platform is exactly when this is easiest to forget: the author is thinking about the
 * renderer, and the fixture is a separate mechanical step at the end. So the convention is checked
 * here rather than remembered.
 */
@DisplayName("The basic example opts into every platform")
class ExampleOptInCoverageTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /**
     * Opt-in keys {@code examples/basic} cannot or should not carry, each with the reason. An entry
     * here is a decision; anything else absent is the convention having lapsed again.
     */
    private static final Map<String, String> NOT_APPLICABLE = Map.of(
        "root_index",
        "the lean indexed root aggregate is a multi-module construct and basic/ is one module; "
            + "examples/multimodule-indexed is the fixture for it",
        "locks_report",
        ".vibetags-locks is an enforcement baseline for a CI diff guard rather than a platform "
            + "file, and examples/enforcing is the fixture that exercises it");

    @Test
    void basicExampleOptsIntoEveryPlatform() throws IOException {
        Path example = REPO_ROOT.resolve("examples/basic");
        assumeTrue(Files.isDirectory(example), "repo layout not reachable; skipping");

        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(example);
        List<String> missing = new ArrayList<>();
        for (String key : ServiceRegistry.optInKeys()) {
            if (NOT_APPLICABLE.containsKey(key)) {
                continue;
            }
            Path target = serviceFiles.get(key);
            if (target != null && !Files.exists(target)) {
                missing.add(key + " -> " + example.relativize(target).toString().replace('\\', '/'));
            }
        }

        assertTrue(missing.isEmpty(),
            "examples/basic is the exhaustive fixture, so a platform missing from it has no "
                + "committed output, no byte-for-byte drift gate in CI, and no example a user can "
                + "copy. Create the opt-in file (an empty `.vibetags` inside the directory, or the "
                + "empty file itself), rebuild the example, and commit what it generates:\n  "
                + String.join("\n  ", missing)
                + "\nIf a platform genuinely does not belong here, add it to NOT_APPLICABLE with "
                + "the reason and name the example that does cover it.");
    }

    /**
     * The reverse: a stale opt-in file for a key no longer in the registry would keep generating
     * output nothing reads, and would survive a platform being renamed.
     */
    @Test
    void everyExemptionStillNamesARealOptInKey() {
        List<String> stale = new ArrayList<>();
        for (String key : NOT_APPLICABLE.keySet()) {
            if (!ServiceRegistry.optInKeys().contains(key)) {
                stale.add(key);
            }
        }
        assertTrue(stale.isEmpty(),
            "NOT_APPLICABLE exempts keys that are no longer opt-in keys at all, so the exemption is "
                + "load-bearing for nothing and hides whatever replaced them: " + stale);
    }
}
