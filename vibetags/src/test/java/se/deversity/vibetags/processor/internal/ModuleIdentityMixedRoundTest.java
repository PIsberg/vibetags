package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round handed a module's main and test sources at once cannot route to {@code TESTING.md}.
 *
 * <p>Routing is decided per round, and {@code pickSourceSet} prefers {@code main}, so such a round
 * reports as main and its test-code guardrails stay in the always-loaded files. That loses nothing;
 * what it lacks is a way to tell, which is the whole of issue #780 — an empty {@code TESTING.md}
 * is indistinguishable from a broken feature. {@code isMixedRound} is what the processor warns on.
 *
 * <p>Maven and Gradle compile the two source sets in separate javac invocations, so every round
 * they produce answers {@code false} here. The shapes that answer {@code true} are a hand-written
 * javac line, an IDE, or a tool that does not separate them.
 */
class ModuleIdentityMixedRoundTest {

    private static final Path ROOT = Path.of("module");

    @Test
    @DisplayName("an ordinary single-source-set round is not mixed")
    void aSingleSourceSetRoundIsNotMixed() {
        assertFalse(new ModuleIdentity(ROOT, "main").isMixedRound(),
            "the two-argument constructor is every round a build tool produces");
        assertFalse(new ModuleIdentity(ROOT, "test").isMixedRound());
    }

    @Test
    @DisplayName("the flag survives the record's components")
    void theFlagIsCarried() {
        assertTrue(new ModuleIdentity(ROOT, "main", true).isMixedRound());
        assertFalse(new ModuleIdentity(ROOT, "main", false).isMixedRound());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"test", "integrationTest", "functionalTests", "testFixtures"})
    @DisplayName("the static naming rule agrees with the instance one")
    void theStaticRuleMatchesTheInstanceRule(String sourceSet) {
        assertTrue(ModuleIdentity.isTestSourceSetName(sourceSet),
            "the resolver uses the static form to spot a test source set among several");
        assertTrue(new ModuleIdentity(ROOT, sourceSet).isTestSourceSet(),
            "and it must answer exactly as the instance method does, or a mixed round is missed");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"main", "latest", "contest", "jmh", "generated"})
    @DisplayName("names that only contain the letters are not test source sets")
    void lookalikesAreNotTests(String sourceSet) {
        assertFalse(ModuleIdentity.isTestSourceSetName(sourceSet),
            "a wrong true here would report an ordinary build as mixed and warn about nothing");
        assertFalse(new ModuleIdentity(ROOT, sourceSet).isTestSourceSet());
    }

    @Test
    @DisplayName("a null source set is not a test source set")
    void nullIsNotATest() {
        assertFalse(ModuleIdentity.isTestSourceSetName(null));
        assertFalse(new ModuleIdentity(ROOT, null).isTestSourceSet());
    }
}
