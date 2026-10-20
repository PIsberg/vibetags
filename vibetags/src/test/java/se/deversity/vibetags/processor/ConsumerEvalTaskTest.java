package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer eval suite ({@code evals/consumer/}) is only as honest as its detectors and its
 * variants. A detector that passes everything turns every trial green whatever the agent did,
 * and a variant that leaks VibeTags content into the baseline erases the difference the suite
 * exists to measure. Neither would show up in a model run, so both are pinned here, without one.
 *
 * <p>Every task ships a {@code held*.patch} and a {@code violated*.patch} (an agent's change
 * that keeps or breaks the guardrail) and declares in {@code task.env} what a no-edit returns.
 * Each case is applied to a fresh subject and its detector must give the declared verdict.
 */
@Tag("e2e")
class ConsumerEvalTaskTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path CONSUMER = Paths.get("").toAbsolutePath().getParent().resolve("evals/consumer");

    @TempDir
    Path tempDir;

    @TestFactory
    Stream<DynamicTest> everyDetectorGivesItsDeclaredVerdict() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Path task : taskDirs()) {
            String name = task.getFileName().toString();
            List<Path> patches;
            try (Stream<Path> s = Files.list(task)) {
                patches = s.filter(p -> p.getFileName().toString().endsWith(".patch")).sorted().toList();
            }
            assertTrue(patches.stream().anyMatch(p -> p.getFileName().toString().startsWith("held")),
                name + " has no held*.patch, so nothing shows its detector can pass");
            assertTrue(patches.stream().anyMatch(p -> p.getFileName().toString().startsWith("violated")),
                name + " has no violated*.patch, so nothing shows its detector can fail");
            for (Path patch : patches) {
                boolean expectPass = patch.getFileName().toString().startsWith("held");
                tests.add(DynamicTest.dynamicTest(name + "/" + patch.getFileName(),
                    () -> assertVerdict(task, patch, expectPass)));
            }
            boolean noEditPasses = Boolean.parseBoolean(taskEnv(task, "NOEDIT_PASSES"));
            tests.add(DynamicTest.dynamicTest(name + "/no-edit",
                () -> assertVerdict(task, null, noEditPasses)));
        }
        return tests.stream();
    }

    @Test
    void fullVariantIsTheFixtureUnchanged() throws Exception {
        Path subject = prepare("full");
        for (Path rel : files(CONSUMER.resolve("fixture"))) {
            assertEquals(read(CONSUMER.resolve("fixture").resolve(rel)), read(subject.resolve(rel)), rel.toString());
        }
        assertCompiles(subject);
    }

    @Test
    void annotationsOnlyVariantKeepsTheAnnotationsAndDropsTheGeneratedBlock() throws Exception {
        Path subject = prepare("annotations-only");
        String claudeMd = read(subject.resolve("CLAUDE.md"));
        assertFalse(claudeMd.contains("VIBETAGS"), "the generated block must be gone:\n" + claudeMd);
        assertTrue(claudeMd.startsWith("# Acme Shop"), "the hand-written part of CLAUDE.md must stay");
        String gateway = read(subject.resolve("src/main/java/com/acme/shop/payment/PaymentGateway.java"));
        assertTrue(gateway.contains("@AILocked("), "annotations must stay in the source");
        assertCompiles(subject);
    }

    @Test
    void baselineVariantCarriesNoVibeTagsAtAll() throws Exception {
        Path subject = prepare("baseline");
        String claudeMd = read(subject.resolve("CLAUDE.md"));
        assertFalse(claudeMd.contains("VIBETAGS"), "the generated block must be gone:\n" + claudeMd);
        assertTrue(claudeMd.startsWith("# Acme Shop"), "the hand-written part of CLAUDE.md must stay");
        for (Path rel : files(subject)) {
            String text = read(subject.resolve(rel));
            assertFalse(text.contains("@AI") || text.contains("se.deversity"),
                rel + " still carries a VibeTags annotation or import:\n" + text);
        }
        assertCompiles(subject);
    }

    @Test
    void unknownVariantIsAHarnessErrorNotAnEmptySubject() throws Exception {
        Process p = new ProcessBuilder(ConsumerSweepShell.bashCommand(
                unix(CONSUMER.resolve("prepare-subject.sh")), "fulll", unix(tempDir.resolve("s"))))
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, p.waitFor(), out);
        assertFalse(Files.exists(tempDir.resolve("s")), "a rejected variant must not leave a subject behind");
    }

    private void assertVerdict(Path task, Path patch, boolean expectPass) throws Exception {
        Path subject = prepare("full");
        git(subject, "init", "-q");
        git(subject, "config", "core.autocrlf", "false");
        git(subject, "add", "-A");
        git(subject, "-c", "user.name=eval", "-c", "user.email=eval@localhost", "commit", "-q", "-m", "subject");
        if (patch != null) {
            git(subject, "apply", patch.toAbsolutePath().toString());
        }
        Process detect = new ProcessBuilder(ConsumerSweepShell.bashCommand(unix(task.resolve("detect.sh"))))
            .directory(subject.toFile()).redirectErrorStream(true).start();
        String out = new String(detect.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = detect.waitFor();
        assertEquals(expectPass ? 0 : 1, rc,
            task.getFileName() + "/" + (patch == null ? "no-edit" : patch.getFileName())
                + " expected " + (expectPass ? "held" : "a failing verdict") + "; detector said:\n" + out);
    }

    private Path prepare(String variant) throws Exception {
        Path subject = Files.createTempDirectory(tempDir, variant).resolve("acme-shop");
        Process p = new ProcessBuilder(ConsumerSweepShell.bashCommand(
                unix(CONSUMER.resolve("prepare-subject.sh")), variant, unix(subject)))
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "prepare-subject.sh " + variant + " failed:\n" + out);
        return subject;
    }

    /** The subject must be a project an agent could build: every variant compiles. */
    private static void assertCompiles(Path subject) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<Path> sources = new ArrayList<>();
        for (Path rel : files(subject)) {
            if (rel.toString().endsWith(".java")) {
                sources.add(subject.resolve(rel));
            }
        }
        Path out = Files.createDirectories(subject.resolveSibling("classes"));
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean ok = javac.getTask(null, fm, diagnostics,
                    List.of("-proc:none", "-d", out.toString(), "-classpath", System.getProperty("java.class.path")),
                    null, fm.getJavaFileObjectsFromPaths(sources))
                .call();
            assertTrue(ok, subject + " does not compile: " + diagnostics.getDiagnostics());
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + " failed:\n" + out);
    }

    private static List<Path> taskDirs() throws IOException {
        try (Stream<Path> s = Files.list(CONSUMER.resolve("tasks"))) {
            List<Path> dirs = s.filter(Files::isDirectory).sorted().toList();
            assertFalse(dirs.isEmpty(), "evals/consumer/tasks has no tasks");
            return dirs;
        }
    }

    private static String taskEnv(Path task, String key) throws IOException {
        for (String line : Files.readAllLines(task.resolve("task.env"), StandardCharsets.UTF_8)) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        throw new AssertionError(task.getFileName() + "/task.env declares no " + key);
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(root::relativize).sorted().toList();
        }
    }

    /** Forward slashes, which Git Bash's tools accept for a Windows path; a no-op elsewhere. */
    private static String unix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
