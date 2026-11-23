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
     * The word {@code include} as a whole word, which leaves {@code includeBuild} and
     * {@code includeFlat} out. What follows it is scanned by {@link #includeArguments}, not matched
     * here: a pattern for a comma-separated run of literals needs nested repetition, which SpotBugs
     * rightly flags as open to catastrophic backtracking.
     */
    private static final Pattern GRADLE_INCLUDE = Pattern.compile("\\binclude\\b");

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
     * so a reactor read as a single project (#1120). The word inside a string literal does not
     * count. {@code includeBuild} and {@code includeFlat}
     * are not matched: the first names a composite build, the second a directory beside the root.
     */
    public static List<String> gradleIncludes(Path settings) {
        List<String> names = new ArrayList<>();
        try {
            Uncommented script = Uncommented.of(Files.readString(settings, StandardCharsets.UTF_8));
            // Searched with literal contents blanked, so "include" in a literal is not a declaration;
            // the two forms have the same length, so a match's offset reads the arguments in the other.
            Matcher include = GRADLE_INCLUDE.matcher(script.codeOutsideLiterals());
            while (include.find()) {
                Matcher quoted = QUOTED.matcher(includeArguments(script.code(), include.end()));
                while (quoted.find()) {
                    String directory = projectDirectory(quoted.group(1));
                    if (!directory.isBlank()) {
                        names.add(directory);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return List.of(); // A diagnostic must never be the thing that fails a build.
        }
        return names;
    }

    /**
     * The argument text of the {@code include} ending at {@code from}: up to the closing parenthesis
     * of the call form, or, for Groovy's command form, the run of string literals separated by
     * commas, which may continue over lines. Empty when neither follows.
     */
    static String includeArguments(String text, int from) {
        int i = skipWhitespace(text, from);
        if (i < text.length() && text.charAt(i) == '(') {
            int close = text.indexOf(')', i);
            return close < 0 ? text.substring(i + 1) : text.substring(i + 1, close);
        }
        int end = i;
        while (end < text.length() && (text.charAt(end) == '"' || text.charAt(end) == '\'')) {
            int closing = text.indexOf(text.charAt(end), end + 1);
            int newline = text.indexOf('\n', end + 1);
            if (closing < 0 || (newline >= 0 && newline < closing)) {
                break;
            }
            end = closing + 1;
            int next = skipWhitespace(text, end);
            if (next >= text.length() || text.charAt(next) != ',') {
                break;
            }
            end = skipWhitespace(text, next + 1);
        }
        return text.substring(i, Math.max(i, end));
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** A Gradle project path as the directory it names by default: {@code :libs:core} is {@code libs/core}. */
    private static String projectDirectory(String projectPath) {
        String name = projectPath;
        while (name.startsWith(":")) {
            name = name.substring(1);
        }
        return name.replace(':', '/');
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
