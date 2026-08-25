package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vibetags-parent/pom.xml} is the one place every version is declared. This is what stops
 * that from being merely an intention.
 *
 * <p>Maven inheritance already enforces it for the poms that have the parent — they carry no
 * versions, so they cannot disagree. Two things sit outside that guarantee and are checked here:
 *
 * <ul>
 *   <li><b>The Gradle builds.</b> They cannot inherit from a Maven POM, so every coordinate in them
 *       is a literal that has to be kept in step by hand. That is exactly how the SnakeYAML test
 *       dependency came to exist in {@code pom.xml} and not {@code build.gradle}: the whole Maven
 *       gate passed locally and in CI while every Gradle job failed.</li>
 *   <li><b>The example projects.</b> Deliberately standalone — they exist to show what a real
 *       consumer's build looks like, and a consumer does not inherit from a VibeTags parent. So
 *       their VibeTags versions are literals, and literals go stale: the load-tests harness sat on
 *       {@code 0.9.5} for two releases while CI believed it was gating the branch.</li>
 * </ul>
 *
 * <p>The failure this prevents is not a broken build. It is a build that passes while measuring,
 * gating or shipping the wrong version.
 */
class BuildVersionParityTest {

    private static final Pattern PROPERTY =
        Pattern.compile("<([A-Za-z0-9_.\\-]+)>([^<]*)</\\1>");
    private static final Pattern MANAGED_DEP = Pattern.compile(
        "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>"
            + "\\s*<version>([^<]+)</version>", Pattern.DOTALL);
    private static final Pattern GRADLE_COORD =
        Pattern.compile("['\"]([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)['\"]");
    private static final Pattern POM_VERSION_LITERAL =
        Pattern.compile("^\\s*<version>([^<$][^<]*)</version>\\s*$", Pattern.MULTILINE);

    /**
     * Gradle build files this test knows must exist. Discovery below finds every one in the
     * repository; these are named so that a walk which silently stops finding anything fails
     * loudly instead of passing vacuously.
     */
    private static final List<String> GRADLE_ANCHORS = List.of(
        "vibetags/build.gradle", "vibetags-annotations/build.gradle", "examples/basic/build.gradle",
        "examples/kotlin/build.gradle.kts", "examples/groovy/build.gradle",
        "examples/scala/build.gradle");

    private static final char EQUALS = '=';
    private static final char SINGLE_QUOTE = '\'';
    private static final char DOUBLE_QUOTE = '"';

    /** Directory names whose contents are build output or tool cache, never sources. */
    private static final Set<String> NON_SOURCE_DIRS =
        Set.of(".git", ".gradle", "build", "target", "node_modules");

    /**
     * The Gradle builds that publish a VibeTags artifact, and so must carry the release version.
     * {@code examples/basic/build.gradle} is excluded on purpose: its {@code version = '1.0.0'} is the
     * example project's own version, the way a consumer's would be, and has nothing to do with
     * which VibeTags it depends on.
     */
    private static final List<String> PUBLISHING_GRADLE_FILES = List.of(
        "vibetags/build.gradle", "vibetags-annotations/build.gradle");

    /** Poms that inherit from the parent and must therefore declare no version of their own. */
    private static final List<String> MANAGED_POMS = List.of(
        "vibetags/pom.xml", "vibetags-annotations/pom.xml", "vibetags-bom/pom.xml",
        "vibetags-cli/pom.xml", "load-tests/pom.xml");

    /**
     * Consumer-shaped poms: standalone on purpose, so a user can lift them into their own project
     * and have them work. Not managed, but their VibeTags version must still be current.
     * {@code tools/demo} is here rather than in {@link #MANAGED_POMS} for the same reason as the
     * examples — it is what the demo GIF shows a consumer's build looking like.
     */
    private static final List<String> EXAMPLE_POMS = List.of(
        "examples/basic/pom.xml", "examples/multimodule/pom.xml", "examples/multimodule-indexed/pom.xml",
        "examples/all-tiers/pom.xml", "tools/demo/pom.xml");

    /**
     * Versions a managed pom may still state as a literal, with the reason. Anything else is drift
     * waiting to happen and fails.
     */
    private static final Map<String, String> ALLOWED_LITERALS = Map.of(
        "1.0.0-SNAPSHOT", "load-tests' own artifact version; it is never released");

    // -----------------------------------------------------------------------

    @Test
    void gradleBuildsAgreeWithTheParentOnEveryManagedCoordinate() {
        Map<String, String> managed = managedVersions();
        String revision = properties().get("revision");
        List<String> problems = new ArrayList<>();

        for (String file : gradleFiles()) {
            String text = read(repoRoot().resolve(file));
            Matcher m = GRADLE_COORD.matcher(text);
            while (m.find()) {
                String key = m.group(1) + ":" + m.group(2);
                String actual = m.group(3);
                String expected = key.startsWith("se.deversity.vibetags:")
                    ? revision                       // VibeTags' own artifacts follow ${revision}
                    : managed.get(key);
                if (expected != null && !expected.equals(actual)) {
                    problems.add(file + ": " + key + " is " + actual
                        + " but vibetags-parent declares " + expected);
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "Gradle cannot inherit from the parent POM, so these had to be kept in step by hand and "
                + "were not. CI builds Maven and Gradle, so this fails only in the Gradle job:\n  "
                + String.join("\n  ", problems));
    }

    /**
     * The Gradle builds publish artifacts too, and they set their own {@code version = '...'}. A
     * release that bumps the parent and forgets these ships a Gradle artifact under the previous
     * version — which resolves, installs, and is simply wrong.
     */
    @Test
    void gradleArtifactVersionsMatchTheRelease() {
        String revision = properties().get("revision");
        Pattern declaration = Pattern.compile("^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);
        List<String> problems = new ArrayList<>();

        for (String file : PUBLISHING_GRADLE_FILES) {
            Matcher m = declaration.matcher(read(repoRoot().resolve(file)));
            while (m.find()) {
                if (!revision.equals(m.group(1))) {
                    problems.add(file + ": publishes version " + m.group(1)
                        + " but the release is " + revision);
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "A Gradle build publishing under the wrong version:\n  " + String.join("\n  ", problems));
    }

    /**
     * Gradle's PMD plugin takes its analyser version from {@code toolVersion}, not from a
     * dependency coordinate, so {@link #gradleBuildsAgreeWithTheParentOnEveryManagedCoordinate}
     * never saw it. It drifted: vibetags-annotations sat on PMD 7.24.0 while the parent and
     * vibetags/build.gradle were on 7.26.0, which means the two modules were analysed by different
     * rule sets and a rule added between those releases ran on one module only.
     */
    /**
     * PMD's {@code toolVersion} must be <em>derived</em> from {@code pmd.version} in the parent,
     * never copied.
     *
     * <p>This used to compare the literal against the parent, which is a weaker rule that reads
     * the same. The difference shows up when dependabot bumps {@code pmd.version}: it edits the
     * parent pom and nothing else, because that is where the property lives, and every Gradle
     * file holding a copy of the old number then failed this test. Each PMD bump therefore
     * arrived as a red PR that a human had to hand-fix before it could merge, which is not what
     * an automated bump is for.
     *
     * <p>So the assertion is now about the mechanism rather than the value. A literal fails even
     * if it happens to be correct today, and a file that never mentions {@code pmd.version} fails
     * even if it has no literal - otherwise deleting the derivation would leave nothing to match
     * and this test would pass on a file it no longer checks.
     */
    @Test
    void gradlePmdToolVersionIsDerivedFromTheParentRatherThanCopied() {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String file : gradleFiles()) {
            String text = read(repoRoot().resolve(file));
            if (!text.contains("pmd {")) {
                continue;
            }
            checked++;
            for (String line : text.lines().toList()) {
                String trimmed = line.trim();
                int assignment = trimmed.indexOf(EQUALS);
                if (!trimmed.startsWith("toolVersion") || assignment < 0) {
                    continue;
                }
                if (isQuoted(trimmed.substring(assignment + 1).trim())) {
                    problems.add(file + ": PMD toolVersion is a literal (" + trimmed
                        + "). Read pmd.version from vibetags-parent instead, so that a bump"
                        + " of the parent cannot leave this file behind.");
                }
            }
            if (!text.contains("pmd.version")) {
                problems.add(file + ": applies the pmd plugin but never names pmd.version,"
                    + " so nothing ties its analyser to the version the rest of the build"
                    + " uses.");
            }
        }

        assertTrue(checked >= 2,
            "Expected at least two Gradle modules applying PMD; found " + checked
                + ". A walk that stops finding them passes this test while checking"
                + " nothing.");
        assertTrue(problems.isEmpty(),
            "A Gradle module that a dependabot bump of pmd.version would leave behind: "
                + String.join("; ", problems));
    }

    /**
     * Whether the right-hand side of a {@code toolVersion} assignment is a quoted number.
     *
     * <p>Only the start of the value is looked at, and that is the whole point: the first
     * version of this asked whether the <em>line</em> contained a quote, which rejected
     * {@code toolVersion = pomVersion('pmd.version')} - the very form it exists to require.
     * A derived value may quote a property name; a copied one opens with the quote.
     */
    private static boolean isQuoted(String value) {
        return !value.isEmpty()
            && (value.charAt(0) == SINGLE_QUOTE || value.charAt(0) == DOUBLE_QUOTE);
    }
    @Test
    void managedPomsDeclareNoVersionOfTheirOwn() {
        List<String> problems = new ArrayList<>();
        for (String file : MANAGED_POMS) {
            String text = stripComments(read(repoRoot().resolve(file)));
            Matcher m = POM_VERSION_LITERAL.matcher(text);
            while (m.find()) {
                String literal = m.group(1).trim();
                if (!ALLOWED_LITERALS.containsKey(literal)) {
                    problems.add(file + ": <version>" + literal + "</version> — move it to "
                        + "vibetags-parent/pom.xml and reference it there");
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "A version literal in a pom that inherits from vibetags-parent defeats the point of the "
                + "parent: it will be missed at the next bump.\n  " + String.join("\n  ", problems));
    }

    @Test
    void exampleProjectsTrackTheCurrentRelease() {
        String revision = properties().get("revision");
        List<String> problems = new ArrayList<>();
        for (String file : EXAMPLE_POMS) {
            String text = stripComments(read(repoRoot().resolve(file)));
            Matcher m = Pattern.compile(
                "<(?:vibetags\\.bom\\.version|vibetags\\.version)>([^<]+)<").matcher(text);
            boolean sawOne = false;
            while (m.find()) {
                sawOne = true;
                if (!revision.equals(m.group(1).trim())) {
                    problems.add(file + ": pins VibeTags " + m.group(1).trim()
                        + " but the current release is " + revision);
                }
            }
            if (!sawOne) {
                problems.add(file + ": declares no vibetags.bom.version property — this test can no "
                    + "longer tell whether it is current");
            }
        }
        assertTrue(problems.isEmpty(),
            "The example projects are standalone on purpose, so nothing but this test stops them "
                + "from being left on an old release:\n  " + String.join("\n  ", problems));
    }

    /** The parent must actually be parseable and populated, or every assertion above is vacuous. */
    @Test
    void theParentDeclaresTheVersionsTheseTestsReadFrom() {
        Map<String, String> props = properties();
        assertEquals(true, props.containsKey("revision"), "vibetags-parent declares no <revision>");
        for (String required : Set.of("junit.version", "mockito.version", "snakeyaml.version",
                                      "archunit.version", "async-test-lib.version")) {
            assertTrue(props.containsKey(required),
                "vibetags-parent no longer declares " + required + ", so the Gradle comparison "
                    + "silently stopped checking it");
        }
        assertTrue(managedVersions().size() >= 10,
            "vibetags-parent manages only " + managedVersions().size() + " dependencies — the "
                + "parser has drifted from the file and these tests check almost nothing");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Every Gradle build file in the repository, discovered rather than listed.
     *
     * <p>The list this replaced was hand-kept, and had already fallen behind: the five build files
     * under {@code examples/gradle-*} were added after it and never appended, so each pinned a
     * literal {@code vibetags-bom} version that nothing compared against the parent. A bump that
     * missed one left the example on the previous release, and it still built green: the example's
     * {@code repositories} block falls through {@code mavenLocal()} to Maven Central, where the
     * previous release resolves, and the generated {@code CLAUDE.md} carries no version string for
     * the drift gate to catch. Verified by pinning {@code examples/gradle-multimodule/build.gradle}
     * to the previous release: this test and {@link ReleaseScriptCoverageTest} both passed.
     *
     * <p>Discovery also means the next example is covered the day it is committed, which is the
     * only version of this check that stays true.
     */
    private static List<String> gradleFiles() {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.endsWith(".gradle") || name.endsWith(".gradle.kts");
                })
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .filter(rel -> Arrays.stream(rel.split("/")).noneMatch(NON_SOURCE_DIRS::contains))
                .sorted()
                .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> missing = GRADLE_ANCHORS.stream().filter(a -> !found.contains(a)).toList();
        assertTrue(missing.isEmpty(),
            "The walk over " + root + " did not find Gradle build files known to exist, so every "
                + "assertion reading it is checking less than it claims: " + missing);
        return found;
    }

    private static Path repoRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 4 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("vibetags-parent/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("could not locate vibetags-parent/pom.xml from "
            + System.getProperty("user.dir"));
    }

    private static Map<String, String> properties() {
        String pom = read(repoRoot().resolve("vibetags-parent/pom.xml"));
        Matcher block = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL).matcher(pom);
        Map<String, String> props = new LinkedHashMap<>();
        if (block.find()) {
            Matcher entry = PROPERTY.matcher(stripComments(block.group(1)));
            while (entry.find()) {
                props.put(entry.group(1), entry.group(2).trim());
            }
        }
        return props;
    }

    /** {@code group:artifact} → version, with property references resolved. */
    private static Map<String, String> managedVersions() {
        Map<String, String> props = properties();
        String pom = read(repoRoot().resolve("vibetags-parent/pom.xml"));
        Matcher block = Pattern.compile("<dependencyManagement>(.*?)</dependencyManagement>",
            Pattern.DOTALL).matcher(pom);
        Map<String, String> managed = new LinkedHashMap<>();
        if (block.find()) {
            String body = stripComments(block.group(1)).replaceAll("\\s+", " ");
            Matcher dep = MANAGED_DEP.matcher(body);
            while (dep.find()) {
                managed.put(dep.group(1).trim() + ":" + dep.group(2).trim(),
                    resolve(dep.group(3).trim(), props));
            }
        }
        return managed;
    }

    private static String resolve(String value, Map<String, String> props) {
        Matcher ref = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        StringBuilder out = new StringBuilder();
        while (ref.find()) {
            String replacement = props.get(ref.group(1));
            ref.appendReplacement(out,
                Matcher.quoteReplacement(replacement == null ? ref.group(0) : replacement));
        }
        ref.appendTail(out);
        return out.toString();
    }

    private static String stripComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
