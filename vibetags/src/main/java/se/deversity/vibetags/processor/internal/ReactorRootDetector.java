package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>The same declarations answer the converse question for the root's own round:
 * {@link #declaredModules} lists the subprojects a root's build names, which is how that round
 * knows it has siblings to lock against before any of them has left a lock file behind (#1081).
 */
public final class ReactorRootDetector {

    /** Safety bound for the upward walk (a build's directory depth never comes close). */
    private static final int MAX_WALK_UP = 64;

    /** A quoted project path in a Gradle {@code include} line. */
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");

    /** A Maven {@code <module>}, or a Maven 4 {@code <subproject>}, entry. */
    private static final Pattern MAVEN_MODULE =
        Pattern.compile("<(module|subproject)>\\s*([^<]+?)\\s*</\\1>");

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

    /**
     * The directories {@code root}'s own build declares as modules, relative to it with forward
     * slashes: Maven {@code <module>} and Maven 4 {@code <subproject>} entries in its
     * {@code pom.xml}, then the {@code include}s of {@code settings.gradle} and
     * {@code settings.gradle.kts}. Empty for a single-project build and for a build file this
     * cannot read or decode. A pattern match rather than a parse, like the rest of this class, so a
     * build file it does not fully understand yields no signal instead of an error.
     */
    public static List<String> declaredModules(Path root) {
        List<String> modules = new ArrayList<>();
        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            try {
                Matcher m = MAVEN_MODULE.matcher(Files.readString(pom, StandardCharsets.UTF_8));
                while (m.find()) {
                    modules.add(m.group(2));
                }
            } catch (IOException | RuntimeException unreadable) {
                // No signal: a build file this cannot decode is not evidence of anything.
            }
        }
        for (String settingsName : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(settingsName);
            if (Files.isRegularFile(settings)) {
                modules.addAll(gradleIncludes(settings));
            }
        }
        return modules;
    }

    /** Directory names a Gradle settings file includes, with the leading colon path flattened. */
    public static List<String> gradleIncludes(Path settings) {
        List<String> names = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(settings, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("include")) {
                    continue;
                }
                Matcher m = QUOTED.matcher(trimmed);
                while (m.find()) {
                    String name = m.group(1);
                    while (name.startsWith(":")) {
                        name = name.substring(1);
                    }
                    name = name.replace(':', '/');
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return List.of(); // A diagnostic must never be the thing that fails a build.
        }
        return names;
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
