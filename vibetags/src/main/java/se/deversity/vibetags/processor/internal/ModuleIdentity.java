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
public record ModuleIdentity(Path root, String sourceSet) {

    /** The conventional primary source set; the only one whose sidecar id carries no suffix. */
    public static final String MAIN = "main";

    /** True when this is the module's primary source set (or the source set could not be told). */
    public boolean isMain() {
        return MAIN.equals(sourceSet);
    }
}
