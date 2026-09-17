package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A consumer that pins a JDK range must not be run on an incompatible default JDK, and enforcer
 * {@code RequireJavaVersion} failures must be reported as toolchain errors rather than regressions (#737).
 */
@DisplayName("The consumer sweep handles JDK-pinned consumers and enforcer toolchain errors")
class ConsumerSweepJdkPinningTest {

    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("codekarta is skipped when JDK21_HOME is unset and the java on PATH is another version")
    void unsetJdkEnvVarSkipsPinnedConsumer() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        syntheticRepoAt(root.resolve("codekarta"));

        // The script decides from the java it finds on PATH, not from the JVM running this test,
        // so that java is pinned here. Branching on java.specification.version instead left this
        // test asserting nothing on every JDK 21 leg of CI, including the only Windows and macOS runs.
        Path defaultJava = fakeJavaDir("26.0.1", "DEFAULT-JAVA");
        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta",
            Map.of("JDK21_HOME", ""), defaultJava);

        String row = rowFor(rows, "codekarta");
        String all = String.join(System.lineSeparator(), rows);
        assertEquals("SKIP", resultOf(row),
            "consumer pinning JDK 21 must be skipped on a JDK 26 default without JDK21_HOME (#737). Row was: "
                + row + System.lineSeparator() + all);
        assertTrue(row.contains("JDK21_HOME"),
            "skip message must name the missing env var. Row was: " + row + System.lineSeparator() + all);
    }

    @Test
    @DisplayName("codekarta builds on the default JDK when JDK21_HOME is unset but that JDK is 21")
    void unsetJdkEnvVarBuildsWhenDefaultJdkMatches() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        syntheticRepoAt(root.resolve("codekarta"));

        Path defaultJava = fakeJavaDir("21.0.4", "DEFAULT-JAVA");
        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta",
            Map.of("JDK21_HOME", ""), defaultJava);

        String row = rowFor(rows, "codekarta");
        assertEquals("PASS", resultOf(row),
            "a default JDK that already is the pinned one must be built on, not skipped. Row was: "
                + row + System.lineSeparator() + String.join(System.lineSeparator(), rows));
    }

    @Test
    @DisplayName("codekarta builds on a default JDK 25, inside its 21-25 range, when JDK21_HOME is unset")
    void unsetJdkEnvVarBuildsOnDefaultJdkInsideRange() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        syntheticRepoAt(root.resolve("codekarta"));

        Path defaultJava = fakeJavaDir("25.0.1", "DEFAULT-JAVA");
        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta",
            Map.of("JDK21_HOME", ""), defaultJava);

        String row = rowFor(rows, "codekarta");
        assertEquals("PASS", resultOf(row),
            "codekarta's enforcer allows JDK 21 through 25, so a default JDK 25 must be built on (#743). Row was: "
                + row + System.lineSeparator() + String.join(System.lineSeparator(), rows));
    }

    @Test
    @DisplayName("codekarta is skipped on a default JDK 20, below its 21-25 range, when JDK21_HOME is unset")
    void unsetJdkEnvVarSkipsDefaultJdkBelowRange() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        syntheticRepoAt(root.resolve("codekarta"));

        Path defaultJava = fakeJavaDir("20.0.2", "DEFAULT-JAVA");
        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta",
            Map.of("JDK21_HOME", ""), defaultJava);

        String row = rowFor(rows, "codekarta");
        assertEquals("SKIP", resultOf(row),
            "a default JDK below the pinned range must be skipped, not built on (#743). Row was: "
                + row + System.lineSeparator() + String.join(System.lineSeparator(), rows));
    }

    @Test
    @DisplayName("setting JDK21_HOME runs the build with that JDK's JAVA_HOME and java")
    void settingJdkEnvVarSetsJavaHomeDuringBuild() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        syntheticRepoAt(root.resolve("codekarta"));

        Path pinnedJdk = fakeJavaDir("21.0.4", "PINNED-JAVA").getParent();
        Path defaultJava = fakeJavaDir("26.0.1", "DEFAULT-JAVA");

        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta",
            Map.of("JDK21_HOME", pinnedJdk.toAbsolutePath().toString()), defaultJava);

        String row = rowFor(rows, "codekarta");
        String all = String.join(System.lineSeparator(), rows);
        assertEquals("PASS", resultOf(row),
            "sweep should pass when JDK21_HOME is provided. Row was: " + row + System.lineSeparator() + all);

        Path logFile = tmpDir.resolve("vibetags-sweep/codekarta.log");
        assertTrue(Files.isRegularFile(logFile), "build log must exist");
        String logContent = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(logContent.contains(pinnedJdk.getFileName().toString()),
            "build should run with JAVA_HOME set to JDK21_HOME path. Log was: " + logContent);
        // A build tool that finds java on PATH rather than through JAVA_HOME must get the pinned
        // one too, and the JAVA_HOME assertion above cannot see a dropped PATH export.
        assertTrue(logContent.contains("PINNED-JAVA") && !logContent.contains("DEFAULT-JAVA"),
            "java on PATH during the build must be the one under JDK21_HOME. Log was: " + logContent);
    }

    @Test
    @DisplayName("enforcer RequireJavaVersion failure is reported as a toolchain error, not FAIL")
    void requireJavaVersionFailureReportedAsToolchainErrorNotFail() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        Path repoDir = root.resolve("blindbean");
        syntheticFailingEnforcerRepoAt(repoDir);

        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "blindbean", Map.of(), null);

        String row = rowFor(rows, "blindbean");
        String all = String.join(System.lineSeparator(), rows);

        assertEquals("ERROR", resultOf(row),
            "RequireJavaVersion failure must not be reported as a plain FAIL regression (#737). Row was: "
                + row + System.lineSeparator() + all);
        assertTrue(row.contains("toolchain") || row.contains("RequireJavaVersion"),
            "row notes must explain that failure was a toolchain error. Row was: "
                + row + System.lineSeparator() + all);
    }

    private static List<String> runSweep(Path root, Path tmpDir, String version, String repo,
                                         Map<String, String> extraEnv, Path pathPrefix)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(ConsumerSweepShell.command(
            "tools/consumer-sweep.sh", version, repo));
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("VIBETAGS_CONSUMER_ROOT", root.toAbsolutePath().toString());
        pb.environment().put("TMPDIR", tmpDir.toAbsolutePath().toString());
        extraEnv.forEach((k, v) -> {
            if (v.isEmpty()) {
                pb.environment().remove(k);
            } else {
                pb.environment().put(k, v);
            }
        });
        if (pathPrefix != null) {
            pb.environment().put("PATH", pathPrefix.toAbsolutePath() + File.pathSeparator
                + pb.environment().getOrDefault("PATH", ""));
        }
        Process sweep = pb.start();
        String out = new String(sweep.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        sweep.waitFor();
        return out.lines().toList();
    }

    /** The RESULT column of a sweep row, compared whole so a change in column padding cannot hide it. */
    private static String resultOf(String row) {
        String[] columns = row.trim().split("\\s+");
        return columns.length > 1 ? columns[1] : "";
    }

    /**
     * A {@code bin} directory holding a {@code java} that reports {@code version} the way a real
     * JDK does and prints {@code marker}, so a build log shows which java ran.
     */
    private static Path fakeJavaDir(String version, String marker) throws IOException {
        Path bin = Files.createDirectories(Files.createTempDirectory("fake-jdk-").resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\n"
            + "echo 'openjdk version \"" + version + "\" 2026-01-20' >&2\n"
            + "echo " + marker + "\n");
        java.toFile().setExecutable(true);
        return bin;
    }

    private static String rowFor(List<String> rows, String repo) {
        Optional<String> row = rows.stream().filter(l -> l.startsWith(repo + " ")).findFirst();
        assertTrue(row.isPresent(),
            "the sweep printed no row at all for " + repo + ". Row output: "
                + String.join(System.lineSeparator(), rows));
        return row.get();
    }

    private static void syntheticRepoAt(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        git(dir, "init", "-q");
        Files.writeString(dir.resolve("pom.xml"),
            "<project><properties><vibetags.version>1.0.0</vibetags.version></properties></project>");
        Path mvnw = dir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho \"BUILD JAVA_HOME=$JAVA_HOME\"\njava -version 2>&1\nexit 0\n");
        mvnw.toFile().setExecutable(true);
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho \"BUILD JAVA_HOME=$JAVA_HOME\"\njava -version 2>&1\nexit 0\n");
        gradlew.toFile().setExecutable(true);

        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "init");
        git(dir, "update-ref", "refs/remotes/origin/main", "HEAD");
    }

    private static void syntheticFailingEnforcerRepoAt(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        git(dir, "init", "-q");
        Files.writeString(dir.resolve("pom.xml"),
            "<project><properties><vibetags.version>1.0.0</vibetags.version></properties></project>");
        Path mvnw = dir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\n"
            + "echo \"[ERROR] Rule 1: org.apache.maven.enforcer.rules.version.RequireJavaVersion failed\"\n"
            + "exit 1\n");
        mvnw.toFile().setExecutable(true);

        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "init");
        git(dir, "update-ref", "refs/remotes/origin/main", "HEAD");
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor() == 0, "git " + String.join(" ", args) + " failed: " + log);
    }
}
