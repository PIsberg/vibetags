package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which source sets count as test code.
 *
 * <p>This decides whether a round's guardrails are routed to {@code TESTING.md}, so a false
 * positive moves production guardrails out of the always-loaded files and a false negative leaves
 * test guardrails in them. The rule is a naming convention, not a substring match: Gradle names
 * custom test source sets {@code integrationTest} or {@code functionalTests}, while {@code latest}
 * and {@code contest} merely contain the letters.
 */
class ModuleIdentityTest {

    private static final Path ROOT = Path.of("module");

    @ParameterizedTest
    @ValueSource(strings = {"test", "integrationTest", "functionalTests", "testFixtures"})
    void testSourceSetsAreRecognised(String sourceSet) {
        assertTrue(new ModuleIdentity(ROOT, sourceSet).isTestSourceSet(),
            sourceSet + " holds test code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "jmh", "generated", "latest", "contest", "tests", "attest", "",
        "   "})
    void otherSourceSetsAreNot(String sourceSet) {
        assertFalse(new ModuleIdentity(ROOT, sourceSet).isTestSourceSet(),
            "'" + sourceSet + "' is not a test source set");
    }

    @Test
    void aMissingSourceSetIsNotATestSourceSet() {
        assertFalse(new ModuleIdentity(ROOT, null).isTestSourceSet());
    }
}
