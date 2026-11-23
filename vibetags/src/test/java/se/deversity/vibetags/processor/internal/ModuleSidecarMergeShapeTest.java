package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The exact text {@code ModuleSidecar.mergeFor} writes, character for character.
 *
 * <p>{@code MultiModuleAggregationTest} and {@code MultiModuleYamlValidityTest} say what a merged
 * file must contain and that it parses. They do not say in which order, with which blank lines, or
 * where a lean-index pointer lands inside its region, and those are what a consumer sees as a diff
 * in every generated file when the merge is rearranged. Each case here compares the whole result,
 * so a change in shape fails on the case that shows it.
 */
class ModuleSidecarMergeShapeTest {

    private static final String FRONT_MATTER = "---\ntrigger: always_on\n---";

    private static final String GRAPH_POINTER = "Guardrails for module `module-graph` are maintained in that module's"
        + " own files, in the scoped rules under `module-graph/.claude/rules/` (loaded automatically when you open a"
        + " matching source file). Consult those for this module's full guardrails.";

    private static ModuleSidecar sidecar(String moduleId, String regionId, String serviceKey, String body) {
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, regionId, regionId);
        sidecar.putBody(serviceKey, body);
        return sidecar;
    }

    @Test
    @DisplayName("hash style: each region between its own markers, bodies stripped, no blank line between regions")
    void twoRegionsInHashStyle() {
        String merged = ModuleSidecar.mergeFor("cursor", List.of(
            sidecar("alpha", "alpha", "cursor", "alpha rule one\nalpha rule two\n"),
            sidecar("beta", "beta", "cursor", "\n\nbeta rule\n")), false);

        assertEquals("# VIBETAGS-MODULE: alpha\n"
            + "alpha rule one\nalpha rule two\n"
            + "# VIBETAGS-MODULE-END: alpha\n"
            + "# VIBETAGS-MODULE: beta\n"
            + "beta rule\n"
            + "# VIBETAGS-MODULE-END: beta", merged);
    }

    @Test
    @DisplayName("comment style: a module's source sets share one region, joined by a blank line")
    void sourceSetsShareOneRegion() {
        String merged = ModuleSidecar.mergeFor("gemini_md", List.of(
            sidecar("core", "core", "gemini_md", "core main rule"),
            sidecar("core__test", "core", "gemini_md", "core test rule"),
            sidecar("web", "web", "gemini_md", "web rule")), true);

        assertEquals("<!-- VIBETAGS-MODULE: core -->\n"
            + "core main rule\n\ncore test rule\n"
            + "<!-- VIBETAGS-MODULE-END: core -->\n"
            + "<!-- VIBETAGS-MODULE: web -->\n"
            + "web rule\n"
            + "<!-- VIBETAGS-MODULE-END: web -->", merged);
    }

    @Test
    @DisplayName("one region alone is written without markers, even from two source sets")
    void aLoneRegionHasNoMarkers() {
        String merged = ModuleSidecar.mergeFor("gemini_md", List.of(
            sidecar("core", "core", "gemini_md", "core main rule\n"),
            sidecar("core__test", "core", "gemini_md", "  core test rule")), true);

        assertEquals("core main rule\n\ncore test rule", merged);
    }

    @Test
    @DisplayName("shared front matter is written once above the regions, and above a lone region")
    void sharedFrontMatterIsHoisted() {
        ModuleSidecar a = sidecar("a", "a", "devin_safety", FRONT_MATTER + "\n\nNode rule\n");
        ModuleSidecar b = sidecar("b", "b", "devin_safety", FRONT_MATTER + "\n\nCli rule\n");

        assertEquals(FRONT_MATTER + "\n\n"
            + "<!-- VIBETAGS-MODULE: a -->\nNode rule\n<!-- VIBETAGS-MODULE-END: a -->\n"
            + "<!-- VIBETAGS-MODULE: b -->\nCli rule\n<!-- VIBETAGS-MODULE-END: b -->",
            ModuleSidecar.mergeFor("devin_safety", List.of(a, b), true));
        assertEquals(FRONT_MATTER + "\n\nNode rule", ModuleSidecar.mergeFor("devin_safety", List.of(a), true));
    }

    @Test
    @DisplayName("hash style hoists nothing: each region keeps its own front matter")
    void hashStyleKeepsFrontMatterInEachRegion() {
        String merged = ModuleSidecar.mergeFor("devin_safety", List.of(
            sidecar("a", "a", "devin_safety", FRONT_MATTER + "\n\nNode rule\n"),
            sidecar("b", "b", "devin_safety", FRONT_MATTER + "\n\nCli rule\n")), false);

        assertEquals("# VIBETAGS-MODULE: a\n" + FRONT_MATTER + "\n\nNode rule\n# VIBETAGS-MODULE-END: a\n"
            + "# VIBETAGS-MODULE: b\n" + FRONT_MATTER + "\n\nCli rule\n# VIBETAGS-MODULE-END: b", merged);
    }

    @Test
    @DisplayName("a renderer's prologue is hoisted when every region opens with it, and left alone otherwise")
    void thePrologueIsHoistedOnlyWhenEveryRegionHasIt() {
        String prologue = PlatformRendererRegistry.filePrologueFor("testing");
        ModuleSidecar a = sidecar("a", "a", "testing", prologue + "\n\nA test rule");

        assertEquals(prologue.strip() + "\n\n"
            + "<!-- VIBETAGS-MODULE: a -->\nA test rule\n<!-- VIBETAGS-MODULE-END: a -->\n"
            + "<!-- VIBETAGS-MODULE: b -->\nB test rule\n<!-- VIBETAGS-MODULE-END: b -->",
            ModuleSidecar.mergeFor("testing",
                List.of(a, sidecar("b", "b", "testing", prologue + "\n\nB test rule")), true));

        assertEquals("<!-- VIBETAGS-MODULE: a -->\n" + prologue + "\n\nA test rule\n<!-- VIBETAGS-MODULE-END: a -->\n"
            + "<!-- VIBETAGS-MODULE: b -->\nB test rule, older renderer\n<!-- VIBETAGS-MODULE-END: b -->",
            ModuleSidecar.mergeFor("testing",
                List.of(a, sidecar("b", "b", "testing", "B test rule, older renderer")), true),
            "a region that never wrote the prologue must not have its first characters cut off for it");
    }

    /** A reactor root opted into the lean index, with module-graph keeping its own scoped rules. */
    private static void leanRoot(Path root) throws IOException {
        Files.createDirectories(root.resolve("module-graph/.claude/rules"));
        Files.createDirectories(root.resolve("module-cli"));
        Files.createFile(root.resolve(".vibetags-root-index"));
        Files.createFile(root.resolve("TESTING.md"));
    }

    private static void saveLinked(Path root, String moduleId, String body, String digest) throws IOException {
        ModuleSidecar sidecar = sidecar(moduleId, "module-graph", "claude", body);
        sidecar.putIndexDigest("claude", digest);
        sidecar.save(root);
    }

    @Test
    @DisplayName("lean index: a linked module gives each source set's digest, then one pointer; others stay embedded")
    void aLinkedModuleGivesItsDigestsThenOnePointer(@TempDir Path root) throws IOException {
        leanRoot(root);
        saveLinked(root, "module-graph", "GRAPH MAIN BODY", "GRAPH MAIN DIGEST");
        saveLinked(root, "module-graph__test", "GRAPH TEST BODY", "GRAPH TEST DIGEST");
        sidecar("module-cli", "module-cli", "claude", "CLI BODY").save(root);

        assertEquals("<!-- VIBETAGS-MODULE: module-cli -->\nCLI BODY\n<!-- VIBETAGS-MODULE-END: module-cli -->\n"
            + "<!-- VIBETAGS-MODULE: module-graph -->\n"
            + "GRAPH MAIN DIGEST\n\nGRAPH TEST DIGEST\n\n" + GRAPH_POINTER + "\n"
            + "<!-- VIBETAGS-MODULE-END: module-graph -->",
            ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true));
    }

    @Test
    @DisplayName("lean index: a linked module keeps its markers even when it is the only region")
    void aLoneLinkedModuleKeepsItsMarkers(@TempDir Path root) throws IOException {
        leanRoot(root);
        saveLinked(root, "module-graph", "GRAPH MAIN BODY", "GRAPH MAIN DIGEST");

        assertEquals("<!-- VIBETAGS-MODULE: module-graph -->\n"
            + "GRAPH MAIN DIGEST\n\n" + GRAPH_POINTER + "\n"
            + "<!-- VIBETAGS-MODULE-END: module-graph -->",
            ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true),
            "a pointer with no module marker around it no longer says whose guardrails it stands for");
    }

    @Test
    @DisplayName("lean index: a linked module with no safety digest gives its pointer alone")
    void aLinkedModuleWithNoDigestGivesItsPointerAlone(@TempDir Path root) throws IOException {
        leanRoot(root);
        sidecar("module-graph", "module-graph", "claude", "GRAPH MAIN BODY").save(root);

        assertEquals("<!-- VIBETAGS-MODULE: module-graph -->\n" + GRAPH_POINTER + "\n"
            + "<!-- VIBETAGS-MODULE-END: module-graph -->",
            ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true));
    }

    @Test
    @DisplayName("lean index: the root module's own guardrails stay inline even when the root has scoped rules")
    void theRootModuleIsNeverLinked(@TempDir Path root) throws IOException {
        leanRoot(root);
        Files.createDirectories(root.resolve(".claude/rules"));
        ModuleSidecar rootModule = new ModuleSidecar("_root_", "", "_root_");
        rootModule.putBody("claude", "ROOT BODY");
        rootModule.save(root);
        sidecar("module-graph", "module-graph", "claude", "GRAPH MAIN BODY").save(root);

        assertEquals("<!-- VIBETAGS-MODULE: _root_ -->\nROOT BODY\n<!-- VIBETAGS-MODULE-END: _root_ -->\n"
            + "<!-- VIBETAGS-MODULE: module-graph -->\n" + GRAPH_POINTER + "\n"
            + "<!-- VIBETAGS-MODULE-END: module-graph -->",
            ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true),
            "the root's guardrails are kept nowhere else, so a pointer would lose them");
    }

    @Test
    @DisplayName("lean index: a module is linked only from the aggregates it contributes to")
    void aModuleIsLinkedOnlyWhereItContributes(@TempDir Path root) throws IOException {
        leanRoot(root);
        Files.createDirectories(root.resolve("module-graph/.cursor/rules"));
        sidecar("module-graph", "module-graph", "claude", "GRAPH MAIN BODY").save(root);
        sidecar("module-cli", "module-cli", "cursor", "CLI CURSOR BODY").save(root);

        assertEquals("CLI CURSOR BODY", ModuleSidecar.mergeFor("cursor", ModuleSidecar.readAll(root), true),
            "module-graph wrote nothing for .cursorrules, so it has no region there to point from");
    }
}
