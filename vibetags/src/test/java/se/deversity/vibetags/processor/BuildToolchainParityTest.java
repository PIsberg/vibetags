package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every module that compiles Java in this repo runs the same static-analysis stack. Nothing in
 * Maven enforces that: each module declares its own plugins, so a module can quietly run fewer
 * checks than its siblings and still go green.
 *
 * <p>That is not hypothetical. Before this test existed, {@code vibetags-annotations} and
 * {@code vibetags-cli} had Checkstyle and PMD but no SpotBugs, no Find Security Bugs and no Error
 * Prone, which meant the only module in the repo that turns user-supplied argv into filesystem
 * writes (the CLI) was the one with no security detectors pointed at it. Nothing reported a gap,
 * because a check that was never configured cannot fail.
 *
 * <p>The {@code .mvn/jvm.config} check is the subtlest of the three. Error Prone needs the
 * {@code --add-exports} flags in that file to reach javac's internals; without them it does not
 * fail, it simply does not run. Since Maven reads {@code .mvn/jvm.config} from the directory the
 * build is invoked in, each module needs its own copy, and three copies of a file is three chances
 * for one to drift. A module whose copy lost a line would keep compiling, keep passing, and stop
 * being checked.
 */
class BuildToolchainParityTest {

    /**
     * Modules deliberately exempt from the stack, each with the reason it is exempt.
     *
     * <p>Empty today, and kept as the shape the exemption must take: a named entry with a reason,
     * not a module quietly missing from a list. A module that belongs here should be added here
     * rather than by narrowing {@link #compilingModules()}.
     */
    private static final Map<String, String> EXEMPT = Map.of();

    /**
     * The modules that compile Java and therefore owe the full stack, derived from the tree.
     *
     * <p>Derived, not listed, and that is the whole point of #805. This was
     * {@code List.of("vibetags", "vibetags-annotations", "vibetags-cli", "vibetags-ksp")}, and
     * {@code load-tests} was not in it: roughly 2000 lines of Java, including the fixtures that
     * decide what every recorded baseline actually measured, ran no Checkstyle, no PMD, no
     * SpotBugs and no Error Prone. Nothing failed, because this guard cannot fail for a module
     * nobody remembered to add, which is the same failure mode it exists to catch one level down.
     *
     * <p>A directory qualifies when it holds a {@code pom.xml} and at least one {@code .java} file
     * under {@code src/}. That leaves {@code vibetags-bom} and {@code vibetags-parent} out on their
     * own terms rather than by name, and puts a new module in the moment it has a source file.
     *
     * <p>Top level only. The projects under {@code examples/} are consumer fixtures: they exist to
     * be compiled the way a consumer's project is, with VibeTags as a dependency and none of this
     * repository's own analysis, and holding them to it would measure the wrong thing.
     */
    private static List<String> compilingModules() {
        try (Stream<Path> entries = Files.list(repoRoot())) {
            List<String> modules = entries
                .filter(Files::isDirectory)
                .filter(dir -> Files.isRegularFile(dir.resolve("pom.xml")))
                .filter(BuildToolchainParityTest::hasJavaSources)
                .map(dir -> String.valueOf(dir.getFileName()))
                .filter(name -> !EXEMPT.containsKey(name))
                .sorted()
                .toList();
            assertFalse(modules.isEmpty(),
                "no compiling modules were found under " + repoRoot() + ". This test derives its "
                    + "own list, so an empty one means the derivation is broken and every "
                    + "assertion below is vacuously true.");
            return modules;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether {@code module} has at least one {@code .java} file under {@code src/}. */
    private static boolean hasJavaSources(Path module) {
        Path src = module.resolve("src");
        if (!Files.isDirectory(src)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(src)) {
            return files.anyMatch(p -> String.valueOf(p.getFileName()).endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The derivation itself, pinned. Everything else here iterates {@link #compilingModules()}, so
     * a derivation that quietly returned too few modules would make every other test pass while
     * checking less, which is precisely the bug this file is about.
     */
    @Test
    void theModuleListIsDerivedAndCoversEveryJavaModule() {
        List<String> modules = compilingModules();

        for (String known : List.of("vibetags", "vibetags-annotations", "vibetags-cli",
                                    "vibetags-ksp", "load-tests")) {
            assertTrue(modules.contains(known),
                known + " compiles Java but the derived module list missed it: " + modules);
        }
        for (String noJava : List.of("vibetags-bom", "vibetags-parent")) {
            assertFalse(modules.contains(noJava),
                noJava + " has no Java sources and should not be held to the stack: " + modules);
        }
    }

    /**
     * Error Prone settings that must hold in every module. Per-module {@code -Xep:...:OFF} entries
     * are deliberately not listed: those are local judgements, documented where they are made.
     */
    private static final List<String> ERROR_PRONE_INVARIANTS = List.of(
        "-Xplugin:ErrorProne",
        // Removed in JDK 26; every module needs the same workaround or the JDK 26 leg breaks.
        "-Xep:UnsafeFinalization:OFF",
        // ERROR, not WARNING: a nullability warning nobody must fix is one people learn to scroll past.
        "-Xep:NullAway:ERROR",
        "-XepOpt:NullAway:AnnotatedPackages=se.deversity.vibetags",
        "-XepOpt:NullAway:JSpecifyMode=true");

    private static final List<String> SPOTBUGS_INVARIANTS = List.of(
        "<artifactId>spotbugs-maven-plugin</artifactId>",
        "<artifactId>findsecbugs-plugin</artifactId>",
        "<effort>Max</effort>",
        "<threshold>Low</threshold>",
        "<failOnError>true</failOnError>");

    private static final List<String> PMD_INVARIANTS = List.of(
        "<artifactId>maven-pmd-plugin</artifactId>",
        "<ruleset>${project.basedir}/../pmd-ruleset.xml</ruleset>",
        "<failOnViolation>true</failOnViolation>",
        "<goal>check</goal>",
        "<goal>cpd-check</goal>");

    @Test
    void everyCompilingModule_runsErrorProneWithTheSameNullAwaySettings() {
        for (String module : compilingModules()) {
            String pom = read(repoRoot().resolve(module + "/pom.xml"));
            for (String invariant : ERROR_PRONE_INVARIANTS) {
                assertTrue(pom.contains(invariant),
                    module + "/pom.xml is missing the Error Prone setting " + invariant
                        + ". Every module runs the same compiler checks; see the javadoc on this test.");
            }
        }
    }

    @Test
    void everyCompilingModule_runsSpotBugsWithFindSecurityBugs() {
        for (String module : compilingModules()) {
            String pom = read(repoRoot().resolve(module + "/pom.xml"));
            for (String invariant : SPOTBUGS_INVARIANTS) {
                assertTrue(pom.contains(invariant),
                    module + "/pom.xml is missing the SpotBugs setting " + invariant
                        + ". A module without bytecode analysis is not a module that passed it.");
            }
        }
    }

    @Test
    void everyCompilingModule_runsPmdAndCpdAgainstTheSharedRuleset() {
        for (String module : compilingModules()) {
            String pom = read(repoRoot().resolve(module + "/pom.xml"));
            for (String invariant : PMD_INVARIANTS) {
                assertTrue(pom.contains(invariant),
                    module + "/pom.xml is missing the PMD setting " + invariant
                        + ". One ruleset, applied everywhere, is the point of pmd-ruleset.xml.");
            }
            assertTrue(pom.contains("<configLocation>${project.basedir}/../checkstyle.xml</configLocation>"),
                module + "/pom.xml does not point Checkstyle at the shared checkstyle.xml.");
        }
    }

    /**
     * Error Prone reaches javac's internals through the {@code --add-exports} flags in
     * {@code .mvn/jvm.config}. Maven reads that file from the invocation directory, so each module
     * carries its own copy, and a copy that drifts disables Error Prone silently rather than
     * failing. Byte equality is the only cheap way to say the three have not drifted.
     */
    @Test
    void everyCompilingModule_carriesTheSameJvmConfigErrorProneNeeds() {
        Path reference = repoRoot().resolve("vibetags/.mvn/jvm.config");
        String expected = read(reference).replace("\r\n", "\n");
        assertTrue(expected.contains("--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"),
            reference + " no longer looks like the Error Prone export set; update this test deliberately.");

        for (String module : compilingModules()) {
            Path config = repoRoot().resolve(module + "/.mvn/jvm.config");
            assertTrue(Files.isRegularFile(config),
                config + " is missing. Without it Error Prone does not fail in " + module
                    + ", it silently does not run.");
            assertEquals(expected, read(config).replace("\r\n", "\n"),
                config + " has drifted from " + reference
                    + ". Error Prone needs every one of these exports; a module missing one stops"
                    + " being checked without saying so.");
        }
    }

    /**
     * Gradle gives a test worker {@code -Xmx512m} unless the build file says otherwise. Surefire
     * gives its fork the JVM default, a quarter of the machine's RAM. Left to the defaults the two
     * build systems therefore run this suite, concurrently and with the JaCoCo agent attached,
     * under heaps that differ by roughly an order of magnitude, and only one of them can run out.
     *
     * <p>What was observed: a Gradle leg failed two arbitrary transitive end-to-end tests on a
     * commit whose tree was byte-identical to an earlier green run, every Maven leg stayed green,
     * and a re-run of the same commit passed. The console format at the time printed no assertion
     * message, so the heap is the leading explanation rather than a proven one. Pinning it is what
     * makes the Gradle legs test the same thing the Maven legs do, rather than also testing how
     * close Gradle's default sits to this suite's peak footprint.
     */
    @Test
    void theGradleTestWorker_pinsAnExplicitHeapRatherThanTakingGradlesDefault() {
        String gradle = read(repoRoot().resolve("vibetags/build.gradle"));
        assertTrue(gradle.contains("maxHeapSize"),
            "vibetags/build.gradle no longer pins maxHeapSize on the test task, so Gradle's 512m"
                + " default is back and the Gradle legs run this suite under a heap Maven's legs"
                + " never see. See the javadoc on this test for what that failure looks like.");
    }

    // -----------------------------------------------------------------------

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

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
