package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parts of the cross-module body merge that {@code ModuleSidecarMergeShapeTest} does not pin:
 * what a {@code WholeFileMerge} is handed ({@link ModuleSidecar#contributionsFor}), a module's own
 * nested output across its source sets ({@link ModuleSidecar#mergeModuleBodies}), the unrouted
 * body a routed round falls back to once {@code TESTING.md} is gone, and the one YAML document a
 * YAML platform is merged into. Exact output, so a refactor of the merge that changes a byte fails.
 */
@DisplayName("Sidecar body merge: whole-file contributions, module bodies, fallbacks, YAML")
class ModuleSidecarBodyMergeTest {

    // ---------------------------------------------------------------- contributionsFor

    @Test
    @DisplayName("contributions are (region, body) in sidecar order, one per region with a body")
    void contributionsAreOnePerRegionInOrder() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("b", "b", "pr_agent", "b doc"),
            sidecar("a", "a", "pr_agent", "a doc"),
            sidecar("c", "c", "claude", "c has nothing for pr_agent"),
            sidecar("d", "d", "pr_agent", "   "));

        assertEquals(List.of(entry("b", "b doc"), entry("a", "a doc")),
            ModuleSidecar.contributionsFor("pr_agent", sidecars));
    }

    @Test
    @DisplayName("a region's second source set is offered as its own entry, after the first, unstripped")
    void aSecondSourceSetIsASecondEntry() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("core", "core", "pr_agent", "main doc\n"),
            sidecar("other", "other", "pr_agent", "other doc"),
            sidecar("core__test", "core", "pr_agent", "\ntest doc"));

        assertEquals(List.of(entry("core", "main doc\n"), entry("core", "\ntest doc"), entry("other", "other doc")),
            ModuleSidecar.contributionsFor("pr_agent", sidecars));
    }

    @Test
    @DisplayName("no sidecar, or none with a body, contributes nothing")
    void nothingToContribute() {
        assertEquals(List.of(), ModuleSidecar.contributionsFor("pr_agent", List.of()));
        assertEquals(List.of(), ModuleSidecar.contributionsFor("pr_agent", List.of(sidecar("a", "a", "claude", "x"))));
    }

    // ---------------------------------------------------------------- mergeModuleBodies

    @Test
    @DisplayName("a module's own output joins its source sets' module bodies, stripped, and ignores other regions")
    void moduleBodiesJoinOneRegionsSourceSets() {
        ModuleSidecar main = moduleSidecar("core", "core", "claude", "\n  main rules  \n");
        ModuleSidecar other = moduleSidecar("api", "api", "claude", "api rules");
        ModuleSidecar test = moduleSidecar("core__test", "core", "claude", "test rules");
        main.putBody("claude", "reactor body, not the module's own");

        assertEquals("main rules\n\ntest rules",
            ModuleSidecar.mergeModuleBodies("claude", List.of(main, other, test), "core"));
    }

    @Test
    @DisplayName("a module's own output states front matter its source sets share once, at the top")
    void moduleBodiesHoistSharedFrontMatter() {
        String fm = "---\ntrigger: always_on\n---";
        List<ModuleSidecar> sidecars = List.of(
            moduleSidecar("core", "core", "claude", fm + "\n\nmain rules"),
            moduleSidecar("core__test", "core", "claude", fm + "\n\ntest rules"));

        assertEquals(fm + "\n\nmain rules\n\ntest rules",
            ModuleSidecar.mergeModuleBodies("claude", sidecars, "core"));
    }

    @Test
    @DisplayName("front matter that differs per source set is kept in each body")
    void moduleBodiesKeepDifferingFrontMatter() {
        List<ModuleSidecar> sidecars = List.of(
            moduleSidecar("core", "core", "claude", "---\na: 1\n---\n\nmain"),
            moduleSidecar("core__test", "core", "claude", "---\na: 2\n---\n\ntest"));

        assertEquals("---\na: 1\n---\n\nmain\n\n---\na: 2\n---\n\ntest",
            ModuleSidecar.mergeModuleBodies("claude", sidecars, "core"));
    }

    @Test
    @DisplayName("a region with no module body gives the empty string, so the caller uses this round's content")
    void noModuleBodyIsEmpty() {
        ModuleSidecar reactorOnly = sidecar("core", "core", "claude", "reactor body");
        assertEquals("", ModuleSidecar.mergeModuleBodies("claude", List.of(reactorOnly), "core"));
        assertEquals("", ModuleSidecar.mergeModuleBodies("claude", List.of(), "core"));
    }

    // ---------------------------------------------------------------- the TESTING.md fallback

    @Test
    @DisplayName("once TESTING.md is gone, a routed round's unrouted body stands in for its safety half")
    void theUnroutedBodyStandsInOnceTestingIsGone(@TempDir Path root) {
        ModuleSidecar routed = sidecar("core", "core", "claude", "safety half");
        routed.putUnroutedBody("claude", "safety half and test guardrails");
        ModuleSidecar plain = sidecar("api", "api", "claude", "api rules");
        List<ModuleSidecar> sidecars = List.of(routed, plain);

        ModuleSidecar.applyTestingOptInTo(root, sidecars);

        assertEquals("# VIBETAGS-MODULE: core\nsafety half and test guardrails\n# VIBETAGS-MODULE-END: core\n"
                + "# VIBETAGS-MODULE: api\napi rules\n# VIBETAGS-MODULE-END: api",
            ModuleSidecar.mergeFor("claude", sidecars, false));
        assertEquals(List.of("core"), ModuleSidecar.testingFallbackModules("claude", sidecars));
    }

    @Test
    @DisplayName("while TESTING.md is opted in, the routed body is used and the unrouted one is not")
    void theRoutedBodyIsUsedWhileTestingIsOptedIn(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("TESTING.md"));
        ModuleSidecar routed = sidecar("core", "core", "claude", "safety half");
        routed.putUnroutedBody("claude", "safety half and test guardrails");
        List<ModuleSidecar> sidecars = List.of(routed);

        ModuleSidecar.applyTestingOptInTo(root, sidecars);

        assertEquals("safety half", ModuleSidecar.mergeFor("claude", sidecars, true));
        assertEquals(List.of(), ModuleSidecar.testingFallbackModules("claude", sidecars));
    }

    @Test
    @DisplayName("a blank unrouted body is no fallback: the ordinary body stays")
    void aBlankUnroutedBodyIsNoFallback(@TempDir Path root) {
        ModuleSidecar routed = sidecar("core", "core", "claude", "safety half");
        routed.putUnroutedBody("claude", "  ");
        List<ModuleSidecar> sidecars = List.of(routed);

        ModuleSidecar.applyTestingOptInTo(root, sidecars);

        assertEquals("safety half", ModuleSidecar.mergeFor("claude", sidecars, true));
        assertEquals(List.of(), ModuleSidecar.testingFallbackModules("claude", sidecars));
    }

    // ---------------------------------------------------------------- one YAML document

    @Test
    @DisplayName("a YAML platform's regions share one scaffold, each between indented sub-markers")
    void yamlRegionsShareOneScaffold() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("a", "a", "aider_conf", "read:\n  - CONVENTIONS.md\n  - a/NOTES.md"),
            sidecar("b", "b", "aider_conf", "read:\n  - CONVENTIONS.md\n  - b/NOTES.md"));

        assertEquals("read:\n"
                + "  # VIBETAGS-MODULE: a\n  - CONVENTIONS.md\n  - a/NOTES.md\n  # VIBETAGS-MODULE-END: a\n"
                + "  # VIBETAGS-MODULE: b\n  - CONVENTIONS.md\n  - b/NOTES.md\n  # VIBETAGS-MODULE-END: b",
            ModuleSidecar.mergeFor("aider_conf", sidecars, false));
    }

    @Test
    @DisplayName("a YAML platform's lone region is written as it is, with no markers")
    void aLoneYamlRegionIsVerbatim() {
        String doc = "read:\n  - CONVENTIONS.md";
        assertEquals(doc, ModuleSidecar.mergeFor("aider_conf", List.of(sidecar("a", "a", "aider_conf", doc)), false));
    }

    @Test
    @DisplayName("a YAML body without the declared anchor falls back to stacking whole documents")
    void aYamlBodyWithoutItsAnchorIsStacked() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("a", "a", "aider_conf", "something: else"),
            sidecar("b", "b", "aider_conf", "read:\n  - CONVENTIONS.md"));

        assertEquals("# VIBETAGS-MODULE: a\nsomething: else\n# VIBETAGS-MODULE-END: a\n"
                + "# VIBETAGS-MODULE: b\nread:\n  - CONVENTIONS.md\n# VIBETAGS-MODULE-END: b",
            ModuleSidecar.mergeFor("aider_conf", sidecars, false));
    }

    @Test
    @DisplayName("no body for the service anywhere merges to the empty string")
    void nothingMergesToEmpty() {
        assertEquals("", ModuleSidecar.mergeFor("claude", List.of(), true));
        assertEquals("", ModuleSidecar.mergeFor("claude", List.of(sidecar("a", "a", "cursor", "x")), true));
    }

    // ---------------------------------------------------------------- fixtures

    private static ModuleSidecar sidecar(String moduleId, String regionId, String service, String body) {
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, regionId, regionId);
        sidecar.putBody(service, body);
        return sidecar;
    }

    private static ModuleSidecar moduleSidecar(String moduleId, String regionId, String service, String body) {
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, regionId, regionId);
        sidecar.putModuleBody(service, body);
        return sidecar;
    }

    private static Map.Entry<String, String> entry(String region, String body) {
        return new SimpleEntry<>(region, body);
    }
}
