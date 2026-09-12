package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the three exclusion files added for Roo Code, Continue and Augment Code.
 *
 * <p>The defect these guard against is specific and silent. {@code AIIgnoreFormatter} switches on
 * the platform and its {@code default} arm writes nothing, so an ignore file wired into
 * {@code ServiceRegistry} and {@code PlatformRendererRegistry} but missed in the formatter is
 * created, opted into, and left holding a header with no globs under it. Nothing throws. Asserting
 * the file exists would pass; asserting the glob is in it is what does not.
 */
@Tag("e2e")
class IgnoreFileFamilyEndToEndTest {

    private static final List<String> IGNORE_FILES =
        List.of(".rooignore", ".continueignore", ".augmentignore");

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = ProcessorTestHarness.withExampleSources(tempDir);
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void everyIgnoreFileIsWritten() {
        for (String file : IGNORE_FILES) {
            assertTrue(harness.fileExists(file), file + " must be written");
        }
    }

    /** The assertion the formatter's default arm would fail. */
    @Test
    void everyIgnoreFileCarriesTheGlobForAnIgnoredElement() throws IOException {
        for (String file : IGNORE_FILES) {
            String content = harness.readFile(file);
            assertTrue(content.contains("GeneratedMetadata"),
                file + " has a header but no glob under it, which is what a missing "
                    + "AIIgnoreFormatter case looks like. Was:\n" + content);
        }
    }

    /** Each file names its own tool, so a copy-paste in the renderer's name switch shows up. */
    @Test
    void everyIgnoreFileNamesItsOwnTool() throws IOException {
        assertTrue(harness.readFile(".rooignore").contains("Roo Code"), ".rooignore must name Roo Code");
        assertTrue(harness.readFile(".continueignore").contains("Continue"), ".continueignore must name Continue");
        assertTrue(harness.readFile(".augmentignore").contains("Augment Code"), ".augmentignore must name Augment Code");
    }
}
