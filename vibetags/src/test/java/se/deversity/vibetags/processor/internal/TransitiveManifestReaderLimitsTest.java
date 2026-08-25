package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounds the classpath probe runs under, and what it says when it hits one.
 *
 * <p>Every candidate package costs a {@code Filer} lookup against the compile classpath, and the
 * candidate set is derived from imports — so it grows with the size of the project being compiled,
 * not with the number of dependencies that actually publish guardrails. Without a cap a large
 * codebase pays an unbounded number of resource lookups for a feature that will, in almost every
 * case, find nothing. With a cap and no diagnostic, a project quietly stops discovering the
 * dependency guardrails it did have, and the generated files just have less in them.
 *
 * <p>So both halves are the contract: stop at the cap, and say that you did.
 */
class TransitiveManifestReaderLimitsTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    /** The suite runs in parallel, so each test captures a logger of its own. */
    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(
            TransitiveManifestReaderLimitsTest.class.getName() + "." + testInfo.getDisplayName());
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<String> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Counts every lookup and answers "no such resource", the overwhelmingly common real answer. */
    private static final class CountingFiler implements Filer {
        private int lookups;

        @Override
        public FileObject getResource(JavaFileManager.Location location,
                                      CharSequence moduleAndPkg, CharSequence relativeName)
                throws IOException {
            lookups++;
            throw new IOException("no such resource");
        }

        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... originating) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... originating) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileObject createResource(JavaFileManager.Location location,
                                         CharSequence moduleAndPkg, CharSequence relativeName,
                                         Element... originating) {
            throw new UnsupportedOperationException();
        }
    }

    private static List<String> names(int count, String prefix) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add("com.example." + prefix + i);
        }
        return names;
    }

    @Test
    void theProbeStopsAtTheLookupCap() {
        TransitiveManifestReader reader = new TransitiveManifestReader(logger);
        CountingFiler filer = new CountingFiler();

        reader.resolveExplicit(filer, names(TransitiveManifestReader.MAX_LOOKUPS + 500, "pkg"));

        assertEquals(TransitiveManifestReader.MAX_LOOKUPS, filer.lookups,
            "the cap is what bounds a large project's cost; probing past it is unbounded work "
                + "for a feature that usually finds nothing");
        assertTrue(reader.lookupCapHit(),
            "and the reader has to know it stopped early, so the processor can report it");
    }

    @Test
    void hittingTheCapIsReportedOnceWithTheCapItself() {
        TransitiveManifestReader reader = new TransitiveManifestReader(logger);
        CountingFiler filer = new CountingFiler();

        reader.resolveExplicit(filer, names(TransitiveManifestReader.MAX_LOOKUPS + 10, "a"));
        reader.resolveExplicit(filer, names(10, "b"));

        List<String> capWarnings = events().stream()
            .filter(m -> m.contains("reason=lookup-cap")).toList();
        assertEquals(1, capWarnings.size(),
            "one warning, not one per unprobed candidate: " + capWarnings);
        assertTrue(capWarnings.get(0).contains(String.valueOf(TransitiveManifestReader.MAX_LOOKUPS)),
            "the warning names the cap, or a reader cannot tell whether raising it would help: "
                + capWarnings);
    }

    @Test
    void aCandidateIsNeverProbedTwice() {
        // Candidates come from imports, so the same package is proposed by every file that imports
        // from it. Re-probing would multiply the classpath cost by the size of the project.
        TransitiveManifestReader reader = new TransitiveManifestReader(logger);
        CountingFiler filer = new CountingFiler();

        reader.resolveExplicit(filer, List.of("com.acme.crypto", "com.acme"));
        reader.resolveExplicit(filer, List.of("com.acme.crypto", "com.acme"));

        assertEquals(2, filer.lookups,
            "the second round asks for packages already probed and must cost nothing");
        assertFalse(reader.lookupCapHit());
    }

    @Test
    void blankExplicitNamesAreNotProbed() {
        // -Avibetags.manifest.packages is a comma-separated option typed by hand; a trailing comma
        // produces an empty entry, and probing "" is a guaranteed miss with a real cost.
        TransitiveManifestReader reader = new TransitiveManifestReader(logger);
        CountingFiler filer = new CountingFiler();

        reader.resolveExplicit(filer, List.of("com.acme", "", "   ", "com.acme"));

        assertEquals(1, filer.lookups, "one real name, probed once");
    }

    @Test
    void aManifestDirectoryEntryThatIsNotValidJsonIsRejectedByName(@TempDir Path dir)
            throws IOException {
        // The directory fallback reads files a different build produced. A malformed one has to be
        // named and skipped: failing would take down a consuming build over a dependency's bug,
        // and skipping in silence reads like the dependency published nothing.
        Files.writeString(dir.resolve("com.example.broken.json"),
            "{ this is not json at all", StandardCharsets.UTF_8);
        // Written through the producer, so the fixture cannot drift from the real document shape.
        Files.writeString(dir.resolve("com.example.fine.json"),
            TransitiveManifest.toJson("com.example.fine", "", List.of(), "test"),
            StandardCharsets.UTF_8);

        TransitiveManifestReader reader = new TransitiveManifestReader(logger);
        List<String> rejected = reader.resolveDirectory(dir);

        assertEquals(1, rejected.size(), "only the broken one is rejected: " + rejected);
        assertTrue(rejected.get(0).startsWith("com.example.broken.json"),
            "the rejection names the file, or nobody can find it: " + rejected);
    }

    @Test
    void aPathThatIsNotADirectoryRejectsNothingAndReadsNothing(@TempDir Path dir)
            throws IOException {
        Path notADirectory = dir.resolve("manifests");
        Files.writeString(notADirectory, "", StandardCharsets.UTF_8);

        assertEquals(List.of(), new TransitiveManifestReader(logger).resolveDirectory(notADirectory),
            "a mistyped -Avibetags.manifest.dir is not a manifest that failed to parse");
    }

    @Test
    void nonJsonFilesInTheManifestDirectoryAreIgnored(@TempDir Path dir) throws IOException {
        // A build tool's extraction step leaves other things in there; they are not rejections.
        Files.writeString(dir.resolve("README.txt"), "notes", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("nested"));

        assertEquals(List.of(), new TransitiveManifestReader(logger).resolveDirectory(dir));
    }
}
