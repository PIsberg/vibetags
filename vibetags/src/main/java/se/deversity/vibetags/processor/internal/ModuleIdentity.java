package se.deversity.vibetags.processor.internal;

import java.nio.file.Path;

/**
 * Identity of the compilation unit currently being processed: which module it belongs to, and
 * which source set of that module javac was handed.
 *
 * <p>Both halves are load-bearing for multi-module aggregation:
 *
 * <ul>
 *   <li>{@code root} — the module directory, which names the module's sidecar and its region in
 *       the shared guardrail files (issue #278).</li>
 *   <li>{@code sourceSet} — {@code "main"}, {@code "test"}, or whatever directory sits under
 *       {@code src/}. Maven and Gradle run the processor once per source set, in separate javac
 *       invocations that see disjoint sources; without this, the {@code test-compile} round looks
 *       like the same module having lost every main-source annotation, and overwrites it
 *       (<a href="https://github.com/PIsberg/vibetags/issues/330">issue #330</a>).</li>
 * </ul>
 */
public record ModuleIdentity(Path root, String sourceSet, boolean mixedSourceSets) {

    /** A round whose source sets were not mixed, which is every round a build tool produces. */
    public ModuleIdentity(Path root, String sourceSet) {
        this(root, sourceSet, false);
    }

    /** The conventional primary source set; the only one whose sidecar id carries no suffix. */
    public static final String MAIN = "main";

    /** Gradle's shared-test-code source set; named for tests but ending in neither suffix. */
    private static final String TEST_FIXTURES = "testFixtures";

    /**
     * Whether this round compiled test code, which decides if its guardrails are routed to
     * {@code TESTING.md}.
     *
     * <p>A naming convention, deliberately not a substring match: {@code test}, a camel-case
     * {@code Test} or {@code Tests} suffix ({@code integrationTest}, {@code functionalTests}), or
     * {@code testFixtures}. Source sets such as {@code latest} or {@code contest} only contain the
     * letters, and {@code jmh} or {@code generated} are not {@code main} without being tests. A
     * wrong {@code true} moves production guardrails out of the always-loaded files, so anything
     * unrecognised answers {@code false}, which leaves the round unrouted and loses nothing.
     */
    public boolean isTestSourceSet() {
        return isTestSourceSetName(sourceSet);
    }

    /** The same rule, for a source-set name held on its own rather than on an identity. */
    public static boolean isTestSourceSetName(String sourceSet) {
        if (sourceSet == null) {
            return false;
        }
        return "test".equals(sourceSet)
            || sourceSet.endsWith("Test")
            || sourceSet.endsWith("Tests")
            || TEST_FIXTURES.equals(sourceSet);
    }

    /**
     * Whether this one round was handed a module's main sources <em>and</em> a test source set.
     *
     * <p>Maven and Gradle compile them as separate javac invocations, so this is false for every
     * round they produce. A build that compiles both at once — a hand-written javac line, an IDE,
     * a tool that does not separate them — reports the round as {@code main}, because
     * {@code pickSourceSet} prefers it, and its test-code guardrails are therefore never routed to
     * {@code TESTING.md}.
     *
     * <p>That is lossless: the guardrails stay in the always-loaded files, exactly where they were
     * before routing existed. What it is not is visible, which is what this flag is for — an empty
     * {@code TESTING.md} otherwise looks identical to a broken feature.
     */
    public boolean isMixedRound() {
        return mixedSourceSets;
    }
}
