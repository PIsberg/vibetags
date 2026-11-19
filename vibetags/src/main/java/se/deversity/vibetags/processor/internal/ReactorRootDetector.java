package se.deversity.vibetags.processor.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /** A quoted project path in a Gradle {@code include} line. */
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");

    /**
     * One Gradle {@code include}: the parenthesised Kotlin or Groovy call (group 1), or the Groovy
     * command form, a comma-separated run of string literals that may continue over lines (group 2).
     * The word boundary after {@code include} is what leaves {@code includeBuild} out.
     */
    private static final Pattern GRADLE_INCLUDE = Pattern.compile(
        "\\binclude\\b\\s*(?:\\(([^)]*)\\)|((?:\"[^\"\\n]*\"|'[^'\\n]*')(?:\\s*,\\s*(?:\"[^\"\\n]*\"|'[^'\\n]*'))*))");

    /** A Maven {@code <module>}, or a Maven 4 {@code <subproject>}, entry. */
    private static final Pattern MAVEN_MODULE =
        Pattern.compile("<(module|subproject)>\\s*([^<]+?)\\s*</\\1>");

    private ReactorRootDetector() {
    }

    /**
     * Returns the nearest ancestor of {@code moduleRoot} whose build definition declares
     * {@code moduleRoot} as one of its modules, or empty when there is none — which is the
     * ordinary case for a standalone project and for a module that already shares the reactor root.
     */
    public static Optional<Path> findReactorRootAbove(Path moduleRoot) {
        return Ancestors.firstMatching(moduleRoot.getParent(), candidate -> declaresModule(candidate, moduleRoot));
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

    /**
     * Directory names a Gradle settings file includes, with the leading colon path flattened.
     *
     * <p>Read over the whole file with comments removed, not line by line: an {@code include(...)}
     * spread over several lines, as Gradle's own init template writes it, used to yield nothing,
     * so a reactor read as a single project (#1120). {@code includeBuild} and {@code includeFlat}
     * are not matched: the first names a composite build, the second a directory beside the root.
     */
    public static List<String> gradleIncludes(Path settings) {
        List<String> names = new ArrayList<>();
        try {
            Matcher include = GRADLE_INCLUDE.matcher(withoutComments(Files.readString(settings, StandardCharsets.UTF_8)));
            while (include.find()) {
                String arguments = include.group(1) != null ? include.group(1) : include.group(2);
                Matcher m = QUOTED.matcher(arguments);
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

    /**
     * {@code text} with its {@code //} and block comments blanked out, string literals left alone so
     * a URL in a repository declaration is not cut at its {@code //}. Newlines are kept.
     */
    static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && next != 0) {
                    out.append(next);
                    i++;
                } else if (c == quote || c == '\n') {
                    quote = 0;
                }
            } else if (c == '/' && next == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
                out.append(' ');
            } else {
                if (c == '"' || c == '\'') {
                    quote = c;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * True when {@code candidate}'s build files name {@code module} as a participating module. Reads
     * the same declarations as {@link #declaredModules}, so a Maven 4 {@code <subproject>}, a padded
     * {@code <module>} and a Gradle include count here exactly as they do for the root's lock signal,
     * and a Gradle include of a longer name that merely contains this one does not (#1096).
     */
    private static boolean declaresModule(Path candidate, Path module) {
        return relativise(candidate, module)
            .map(relative -> declaredModules(candidate).contains(relative))
            .orElse(false);
    }

    /** Forward-slash path of {@code module} relative to {@code base}, or empty if not below it. */
    private static Optional<String> relativise(Path base, Path module) {
        try {
            String rel = PathStrings.relative(base, module);
            return rel.isEmpty() || rel.startsWith("..") ? Optional.empty() : Optional.of(rel);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
