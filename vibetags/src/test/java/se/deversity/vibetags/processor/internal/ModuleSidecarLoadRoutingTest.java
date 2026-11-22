package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.content.GranularContribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which field {@code ModuleSidecar.load} reads each line of a sidecar into.
 *
 * <p>The verdicts of {@code load} (a sidecar, {@code null}, a sentinel) are held by
 * {@code ModuleSidecarOnRecordAgreementTest} and {@code ModuleSidecarResilienceTest}. Neither says
 * where a value ends up once the file is accepted, and that is what a reader of the format gets
 * wrong quietly: a module body stored as a reactor body is rendered into every sibling's aggregate,
 * and nothing about the file looks malformed. Every case here writes the file by hand, or saves and
 * reloads, and reads the result back through the public getters and through the bytes a second
 * {@code save} writes, which is the only view of the one field that has no getter.
 */
class ModuleSidecarLoadRoutingTest {

    private static final String HEADER = "# version=3\nmoduleId=core__test\nmodulePath=core\nregionId=core\n";

    @TempDir
    Path root;

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Path file(String moduleId) {
        return root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId);
    }

    private ModuleSidecar loadWritten(String content) throws IOException {
        Path path = file("core__test");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        ModuleSidecar loaded = ModuleSidecar.load(path);
        assertNotNull(loaded, "fixture assumption: the file is a readable sidecar");
        assertFalse(loaded == ModuleSidecar.UNREADABLE || loaded == ModuleSidecar.FUTURE_VERSION,
            "fixture assumption: the file is a readable sidecar");
        return loaded;
    }

    /** The bytes {@code sidecar} writes, read from a file nothing else has written to. */
    private String savedBytes(ModuleSidecar sidecar) throws IOException {
        Path path = file(sidecar.getModuleId());
        Files.deleteIfExists(path);
        sidecar.save(root);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("each key is read into its own field, and no value into another key's")
    void eachKeyIsReadIntoItsOwnField() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "claude=" + b64("reactor body") + "\n"
            + "~mod~claude=" + b64("module body") + "\n"
            + "~idx~claude=" + b64("safety digest") + "\n"
            + "~tfull~claude=" + b64("unrouted body") + "\n"
            + "~gran~Foo=" + b64("**/Foo.java\tsrc/**/Foo*.java\nfoo rules") + "\n"
            + "~granname~Foo=" + b64("Foo\nAI rules for Foo") + "\n"
            + "~modgran~Bar=" + b64("**/Bar.java\nbar rules") + "\n"
            + "~modgranname~Bar=" + b64("Bar\nAI rules for Bar") + "\n"
            + "~granular=" + b64("stem-one\nstem-two") + "\n"
            + "~elements=" + b64("com.example.Foo\ncom.example.Bar#run()") + "\n"
            + "~roots=" + b64("core/src/test/java") + "\n"
            + "# end\n");

        assertEquals("core__test", loaded.getModuleId());
        assertEquals("core", loaded.getModulePath());
        assertEquals("core", loaded.getRegionId());
        assertEquals(Map.of("claude", "reactor body"), loaded.getBodies());
        assertEquals(Map.of("claude", "module body"), loaded.getModuleBodies());
        assertEquals(Map.of("claude", "unrouted body"), loaded.getUnroutedBodies());
        assertEquals(Map.of("Foo", new GranularContribution(
                List.of("**/Foo.java", "src/**/Foo*.java"), "foo rules", "Foo", "AI rules for Foo")),
            loaded.getGranularContributions());
        assertEquals(Map.of("Bar", new GranularContribution(
                List.of("**/Bar.java"), "bar rules", "Bar", "AI rules for Bar")),
            loaded.getModuleGranularContributions());
        assertEquals(List.of("stem-one", "stem-two"), List.copyOf(loaded.getGranularStems()));
        assertEquals(List.of("com.example.Foo", "com.example.Bar#run()"), List.copyOf(loaded.getElementIds()));
        assertEquals(Set.of("core/src/test/java"), loaded.getSourceRoots());
        // The safety digest has no getter; the line a second save writes for it is its only view.
        assertTrue(savedBytes(loaded).contains("\n~idx~claude=" + b64("safety digest") + "\n"),
            "the ~idx~ value must come back as the index digest, under the same key");
    }

    @Test
    @DisplayName("a sidecar holding every kind of content writes the same bytes after a reload")
    void everyKindOfContentSurvivesSaveAndLoad() throws IOException {
        ModuleSidecar saved = new ModuleSidecar("core__test", "core", "core");
        saved.putBody("claude", "reactor claude");
        saved.putBody("cursor", "reactor cursor");
        saved.putModuleBody("claude", "module claude");
        saved.putIndexDigest("claude", "digest claude");
        saved.putUnroutedBody("cursor", "unrouted cursor");
        saved.putGranularContribution("Foo",
            new GranularContribution(List.of("**/Foo.java"), "foo rules", "Foo", "AI rules for Foo"));
        saved.putGranularContribution("Plain", new GranularContribution(List.of("**/Plain.java"), "plain rules"));
        saved.putModuleGranularContribution("Bar",
            new GranularContribution(List.of("**/Bar.java"), "bar rules", "Bar", "AI rules for Bar"));
        saved.setGranularStems(new LinkedHashSet<>(List.of("Foo", "Plain", "Bar")));
        saved.setElementIds(new LinkedHashSet<>(List.of("com.example.Foo", "com.example.Bar")));
        saved.setSourceRoots(List.of("core/src/test/java", "core/src/it/java"));
        String written = savedBytes(saved);

        ModuleSidecar loaded = ModuleSidecar.load(file("core__test"));

        assertNotNull(loaded);
        assertEquals(List.of("claude", "cursor"), List.copyOf(loaded.getBodies().keySet()),
            "bodies keep the order of the file, which is the order they are merged in");
        assertEquals(saved.getBodies(), loaded.getBodies());
        assertEquals(saved.getModuleBodies(), loaded.getModuleBodies());
        assertEquals(saved.getUnroutedBodies(), loaded.getUnroutedBodies());
        assertEquals(saved.getGranularContributions(), loaded.getGranularContributions());
        assertEquals(saved.getModuleGranularContributions(), loaded.getModuleGranularContributions());
        assertEquals(List.copyOf(saved.getGranularStems()), List.copyOf(loaded.getGranularStems()));
        assertEquals(List.copyOf(saved.getElementIds()), List.copyOf(loaded.getElementIds()));
        assertEquals(List.copyOf(saved.getSourceRoots()), List.copyOf(loaded.getSourceRoots()));
        assertEquals(written, savedBytes(loaded), "a reload followed by a save must be a fixed point");
    }

    @Test
    @DisplayName("a reserved key no version defines is stored nowhere, whatever its value")
    void aReservedKeyNoVersionDefinesIsNotStored() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "claude=" + b64("reactor body") + "\n"
            + "~future~claude=" + b64("from a newer processor") + "\n"
            + "~future=not base64 at all\n"
            + "# end\n");

        assertEquals(Map.of("claude", "reactor body"), loaded.getBodies(),
            "a reserved key read as a service body is rendered into somebody's guardrail file");
        assertFalse(savedBytes(loaded).contains("~future"), "and it is not carried into the next save");
    }

    @Test
    @DisplayName("a naming line names its contribution whether it comes before or after it")
    void aNamingLineNamesItsContributionOnEitherSide() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "~granname~Before=" + b64("Before\nAI rules for Before") + "\n"
            + "~gran~Before=" + b64("**/Before.java\nbefore rules") + "\n"
            + "~gran~After=" + b64("**/After.java\nafter rules") + "\n"
            + "~granname~After=" + b64("After\nAI rules for After") + "\n"
            + "~modgranname~Before=" + b64("ModBefore\nAI rules for ModBefore") + "\n"
            + "~modgran~Before=" + b64("**/Before.java\nmodule before rules") + "\n"
            + "# end\n");

        assertEquals("Before", loaded.getGranularContributions().get("Before").displayName());
        assertEquals("AI rules for After", loaded.getGranularContributions().get("After").description());
        assertEquals("ModBefore", loaded.getModuleGranularContributions().get("Before").displayName(),
            "a module naming line names the module contribution, not the reactor one of the same stem");
    }

    @Test
    @DisplayName("a naming line with no contribution, or with no second line, names nothing")
    void aNamingLineThatCannotApplyIsDropped() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "~granname~Orphan=" + b64("Orphan\nAI rules for Orphan") + "\n"
            + "~gran~Foo=" + b64("**/Foo.java\nfoo rules") + "\n"
            + "~granname~Foo=" + b64("one line only") + "\n"
            + "# end\n");

        assertEquals(Set.of("Foo"), loaded.getGranularContributions().keySet());
        assertFalse(loaded.getGranularContributions().get("Foo").isNamed());
    }

    @Test
    @DisplayName("a granular contribution with no header line, or no rules, is skipped and the rest is read")
    void aMalformedContributionIsSkipped() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "~gran~NoHeader=" + b64("just one line") + "\n"
            + "~gran~NoRules=" + b64("**/NoRules.java\n   ") + "\n"
            + "~modgran~NoHeader=" + b64("just one line") + "\n"
            + "~gran~Foo=" + b64("**/Foo.java\nfoo rules") + "\n"
            + "claude=" + b64("reactor body") + "\n"
            + "# end\n");

        assertEquals(Set.of("Foo"), loaded.getGranularContributions().keySet());
        assertTrue(loaded.getModuleGranularContributions().isEmpty());
        assertEquals(Map.of("claude", "reactor body"), loaded.getBodies());
    }

    @Test
    @DisplayName("blank entries in a list value are dropped, and a line with no '=' is not a key")
    void blankListEntriesAndKeylessLinesAreDropped() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "\n"
            + "a line with no equals sign\n"
            + "~granular=" + b64("\nstem-one\n  \nstem-two\n") + "\n"
            + "~elements=" + b64("com.example.Foo\n\n") + "\n"
            + "~roots=" + b64("\ncore\\src\\main\\java\n") + "\n"
            + "# end\n");

        assertEquals(List.of("stem-one", "stem-two"), List.copyOf(loaded.getGranularStems()));
        assertEquals(Set.of("com.example.Foo"), loaded.getElementIds());
        assertEquals(Set.of("core/src/main/java"), loaded.getSourceRoots());
        assertTrue(loaded.getBodies().isEmpty(), "a line with no '=' must not become a body");
    }

    @Test
    @DisplayName("a repeated service key keeps its first position and its last value")
    void aRepeatedKeyKeepsItsLastValue() throws IOException {
        ModuleSidecar loaded = loadWritten(HEADER
            + "claude=" + b64("first") + "\n"
            + "cursor=" + b64("cursor body") + "\n"
            + "claude=" + b64("second") + "\n"
            + "# end\n");

        assertEquals(List.of("claude", "cursor"), List.copyOf(loaded.getBodies().keySet()));
        assertEquals("second", loaded.getBodies().get("claude"));
    }

    @Test
    @DisplayName("a sidecar with no region id, or a blank one, is its own region")
    void aMissingRegionIdFallsBackToTheModuleId() throws IOException {
        assertEquals("core__test", loadWritten(
            "# version=3\nmoduleId=core__test\nmodulePath=core\n# end\n").getRegionId());
        assertEquals("core__test", loadWritten(
            "# version=3\nmoduleId=core__test\nmodulePath=core\nregionId=  \n# end\n").getRegionId());
        assertEquals("", loadWritten("# version=3\nmoduleId=core__test\n# end\n").getModulePath(),
            "a sidecar with no modulePath line is the root module's");
    }

    @Test
    @DisplayName("the last readable version header decides whether a trailer was promised")
    void theLastVersionHeaderDecidesTheTrailerPromise() throws IOException {
        Path path = file("core__test");
        Files.writeString(path, "# version=3\nmoduleId=core__test\n# version=2\nclaude=" + b64("body") + "\n");
        ModuleSidecar preTrailer = ModuleSidecar.load(path);
        assertNotNull(preTrailer, "version 2 promises no trailer, so its absence is not a torn write");
        assertEquals("body", preTrailer.getBodies().get("claude"));
        assertNotNull(ModuleSidecar.loadSummary(path, true), "and the summary reads it the same way");

        Files.writeString(path, "# version=2\nmoduleId=core__test\n# version=3\nclaude=" + b64("body") + "\n");
        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(path),
            "version 3 promises a trailer, and this file has none");
        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.loadSummary(path, true));
    }
}
