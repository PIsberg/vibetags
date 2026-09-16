package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("codekarta is skipped when JDK21_HOME is unset and current JDK differs")
    void unsetJdkEnvVarSkipsPinnedConsumer() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        Path repoDir = root.resolve("codekarta");
        syntheticRepoAt(repoDir);

        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        // Pass empty JDK21_HOME and force current java to report version 26
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta", Map.of("JDK21_HOME", ""));

        String row = rowFor(rows, "codekarta");
        String all = String.join(System.lineSeparator(), rows);

        // If current JDK is 26, it must skip rather than building on the wrong JDK
        String javaVersion = System.getProperty("java.specification.version", "");
        if (!"21".equals(javaVersion)) {
            assertTrue(row.contains("SKIP"),
                "consumer pinning JDK 21 must be skipped when running on JDK " + javaVersion
                    + " without JDK21_HOME (#737). Row was: " + row + System.lineSeparator() + all);
            assertTrue(row.contains("requires JDK 21") || row.contains("JDK21_HOME"),
                "skip message must name the required JDK or missing env var. Row was: "
                    + row + System.lineSeparator() + all);
        }
    }

    @Test
    @DisplayName("setting JDK21_HOME sets JAVA_HOME during the build")
    void settingJdkEnvVarSetsJavaHomeDuringBuild() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        Path repoDir = root.resolve("codekarta");
        syntheticRepoAt(repoDir);

        // Create a dummy JDK directory
        Path fakeJdk = Files.createTempDirectory("fake-jdk-21");
        Files.createDirectories(fakeJdk.resolve("bin"));

        Path tmpDir = Files.createTempDirectory("sweep-tmp");
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "codekarta",
            Map.of("JDK21_HOME", fakeJdk.toAbsolutePath().toString()));

        String row = rowFor(rows, "codekarta");
        String all = String.join(System.lineSeparator(), rows);

        assertTrue(row.contains("PASS"),
            "sweep should pass when JDK21_HOME is provided. Row was: "
                + row + System.lineSeparator() + all);

        Path logFile = tmpDir.resolve("vibetags-sweep/codekarta.log");
        assertTrue(Files.isRegularFile(logFile), "build log must exist");
        String logContent = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(logContent.contains(fakeJdk.getFileName().toString()),
            "build should run with JAVA_HOME set to JDK21_HOME path. Log was: " + logContent);
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
        List<String> rows = runSweep(root, tmpDir, "9.9.9", "blindbean", Map.of());

        String row = rowFor(rows, "blindbean");
        String all = String.join(System.lineSeparator(), rows);

        assertFalse(row.startsWith("blindbean              FAIL"),
            "RequireJavaVersion failure must not be reported as a plain FAIL regression (#737). Row was: "
                + row + System.lineSeparator() + all);
        assertTrue(row.contains("toolchain") || row.contains("RequireJavaVersion"),
            "row notes must explain that failure was a toolchain error. Row was: "
                + row + System.lineSeparator() + all);
    }

    private static List<String> runSweep(Path root, Path tmpDir, String version, String repo,
                                         Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "sh", "tools/consumer-sweep.sh", version, repo);
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
        Process sweep;
        try {
            sweep = pb.start();
        } catch (IOException noShell) {
            assumeTrue(false, "no sh on PATH; the Linux CI job runs this");
            return List.of();
        }
        String out = new String(sweep.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        sweep.waitFor();
        return out.lines().toList();
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
        Files.writeString(mvnw, "#!/bin/sh\necho \"BUILD JAVA_HOME=$JAVA_HOME\"\nexit 0\n");
        mvnw.toFile().setExecutable(true);
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho \"BUILD JAVA_HOME=$JAVA_HOME\"\nexit 0\n");
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
