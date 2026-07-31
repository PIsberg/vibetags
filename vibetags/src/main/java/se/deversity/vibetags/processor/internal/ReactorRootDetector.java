package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the reactor root a module <em>should</em> have been contributing to, when it compiled as
 * its own root instead.
 *
 * <p>A module that does not inherit {@code -Avibetags.root} — because it overrides the compiler
 * plugin's {@code compilerArgs} or {@code annotationProcessorPaths}, say — generates a complete,
 * correct set of guardrail files inside its own directory and never reaches the reactor's. Its
 * whole {@code <project_guardrails>} section simply disappears from the merged root, with no NOTE
 * and no WARNING, and everything still compiles green
 * (<a href="https://github.com/PIsberg/vibetags/issues/296">issue #296</a>).
 *
 * <p>The signal has to be specific or the warning is noise: plenty of projects legitimately sit
 * inside a directory that happens to have a build file. So this asks the stronger question —
 * does an ancestor's build definition <em>name this directory as one of its modules</em>? A
 * {@code <module>} entry in a parent {@code pom.xml} or an {@code include} in a
 * {@code settings.gradle(.kts)} is the build declaring the relationship itself.
 */
public final class ReactorRootDetector {

    /** Safety bound for the upward walk (a build's directory depth never comes close). */
    private static final int MAX_WALK_UP = 64;

    private ReactorRootDetector() {
    }

    /**
     * Returns the nearest ancestor of {@code moduleRoot} whose build definition declares
     * {@code moduleRoot} as one of its modules, or {@code null} when there is none — which is the
     * ordinary case for a standalone project and for a module that already shares the reactor root.
     */
    public static @Nullable Path findReactorRootAbove(Path moduleRoot) {
        Path current = moduleRoot.getParent();
        for (int i = 0; current != null && i < MAX_WALK_UP; i++, current = current.getParent()) {
            if (declaresModule(current, moduleRoot)) {
                return current;
            }
        }
        return null;
    }

    /** True when {@code candidate}'s build files name {@code module} as a participating module. */
    private static boolean declaresModule(Path candidate, Path module) {
        String relative = relativise(candidate, module);
        if (relative == null) {
            return false;
        }
        // Maven: <module>sub/dir</module>. Gradle: include("sub:dir") / include ':sub:dir'.
        return contains(candidate.resolve("pom.xml"), "<module>" + relative + "</module>")
            || contains(candidate.resolve("settings.gradle"), gradlePath(relative))
            || contains(candidate.resolve("settings.gradle.kts"), gradlePath(relative));
    }

    /** Forward-slash path of {@code module} relative to {@code base}, or {@code null} if not below it. */
    private static @Nullable String relativise(Path base, Path module) {
        try {
            String rel = base.relativize(module).toString().replace('\\', '/');
            return rel.isEmpty() || rel.startsWith("..") ? null : rel;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Gradle project path for a relative directory: {@code a/b} → {@code a:b}. */
    private static String gradlePath(String relative) {
        return relative.replace('/', ':');
    }

    /**
     * True when {@code file} exists and contains {@code needle}. Deliberately a substring test
     * rather than a parse: this runs inside somebody else's compile, only ever decides whether to
     * print one diagnostic, and must not fail on a build file it does not fully understand.
     */
    private static boolean contains(Path file, String needle) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.contains(needle)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable or non-UTF-8 build file — no signal, no warning.
        }
        return false;
    }
}
