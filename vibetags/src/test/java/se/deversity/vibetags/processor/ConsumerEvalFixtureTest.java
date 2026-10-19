package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer eval fixture ({@code evals/consumer/fixture}) is the subject the consumer proof
 * runs agents against, so its committed guardrail block has to be what the processor generates
 * from its annotations today. A hand-edited or stale block would measure prose VibeTags never
 * wrote. This compiles a copy of the fixture and requires every committed file back unchanged.
 *
 * <p>To regenerate after a rendering change, run this class with
 * {@code -Dvibetags.evals.regenerate=true}; it then writes the regenerated files into the
 * fixture before comparing.
 */
class ConsumerEvalFixtureTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path FIXTURE =
        Paths.get("").toAbsolutePath().getParent().resolve("evals/consumer/fixture");

    @TempDir
    Path tempDir;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void committedFixtureIsWhatTheProcessorGenerates() throws IOException {
        List<Path> committed = committedFiles();
        Path copy = tempDir.resolve("acme-shop");
        for (Path rel : committed) {
            Path target = copy.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(FIXTURE.resolve(rel), target);
        }

        ProcessorTestHarness harness = new ProcessorTestHarness(copy, false);
        for (Path rel : committed) {
            if (rel.toString().endsWith(".java")) {
                harness.addSourceFile(copy.resolve(rel));
            }
        }
        harness.compile();

        if (Boolean.getBoolean("vibetags.evals.regenerate")) {
            for (Path rel : committed) {
                Files.copy(copy.resolve(rel), FIXTURE.resolve(rel),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        String claudeMd = read(copy.resolve("CLAUDE.md"));
        assertTrue(claudeMd.contains("<!-- VIBETAGS-START -->") && claudeMd.contains("PaymentGateway"),
            "the fixture's CLAUDE.md must carry a generated block naming the locked PaymentGateway; got:\n" + claudeMd);
        for (Path rel : committed) {
            assertEquals(read(FIXTURE.resolve(rel)), read(copy.resolve(rel)),
                rel + " differs from what the processor generates; rerun this test with"
                    + " -Dvibetags.evals.regenerate=true and commit the result");
        }
    }

    private static List<Path> committedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            walk.filter(Files::isRegularFile).forEach(p -> files.add(FIXTURE.relativize(p)));
        }
        return files;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
