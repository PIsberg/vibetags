package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.YamlMergeShape;
import se.deversity.vibetags.processor.model.GuardrailModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link YamlMergeShape} describes its renderer's output, which makes it a twin: edit the
 * scaffold and the declaration is quietly wrong, and the only symptom is a reactor build writing a
 * YAML file some other tool cannot read. Nothing in the single-module path would notice.
 *
 * <p>So the build notices instead. These tests render each platform for real and check the
 * declaration against what came out, and they fail a new YAML platform that ships without one.
 */
class YamlMergeShapeContractTest {

    private static final Set<String> ALL_SERVICES = Set.of(
        "sweep", "plandex", "interpreter", "coderabbit", "ellipsis", "roo_modes");

    /** Files that are YAML documents but whose name does not say so. */
    private static final Set<String> YAML_FILES_WITHOUT_A_YAML_SUFFIX = Set.of(".roomodes");

    /**
     * The declared anchor has to be a line the renderer actually writes, exactly once. Once,
     * because the merge splits on its first occurrence: a second one further down would silently
     * move the scaffold boundary and drop everything between them.
     */
    @Test
    void everyDeclaredAnchor_isAWholeLineOfTheRendering() {
        for (Platform platform : platformsWithAShape()) {
            YamlMergeShape shape = shapeOf(platform);
            String rendered = render(platform, populatedModel());
            assertEquals(1, wholeLineCount(rendered, shape.anchor()),
                platform + ": declared anchor '" + shape.anchor() + "' must appear exactly once as a "
                    + "whole line of the rendering, but the renderer wrote:\n" + rendered);
        }
    }

    /**
     * The declared empty body has to be what the renderer emits with nothing to say. Get it wrong
     * and a contributing module's entries end up next to a stray {@code []} or placeholder
     * sentence.
     */
    @Test
    void everyDeclaredEmptyBody_isWhatTheRendererEmitsForAnEmptyModel() {
        for (Platform platform : platformsWithAShape()) {
            YamlMergeShape shape = shapeOf(platform);
            String rendered = render(platform, GuardrailModel.EMPTY);
            String afterAnchor = afterAnchor(rendered, shape.anchor());
            assertEquals(shape.emptyBody().strip(), afterAnchor.strip(),
                platform + ": declared emptyBody does not match what the renderer writes for an "
                    + "empty model — a module contributing nothing would not be recognised as empty");
        }
    }

    /** Contributions must sit at the declared indent, or a sub-marker comment lands in the wrong block. */
    @Test
    void everyDeclaredIndent_matchesTheRenderedContributionIndent() {
        for (Platform platform : platformsWithAShape()) {
            YamlMergeShape shape = shapeOf(platform);
            String firstLine = afterAnchor(render(platform, populatedModel()), shape.anchor())
                .lines().filter(l -> !l.isBlank()).findFirst().orElse("");
            int actual = firstLine.length() - firstLine.stripLeading().length();
            assertEquals(shape.indent(), actual,
                platform + ": declared indent " + shape.indent() + " but the first contributed line is '"
                    + firstLine + "'");
        }
    }

    /**
     * The one that matters when someone adds a platform: a generated YAML file with no shape gets
     * the whole-document concatenation, which is the defect this all exists to prevent.
     */
    @Test
    void everyGeneratedYamlFile_declaresAMergeShape(@TempDir Path root) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Path> service : ServiceRegistry.buildServiceFileMap(root).entrySet()) {
            String fileName = String.valueOf(service.getValue().getFileName());
            boolean isYaml = fileName.endsWith(".yaml") || fileName.endsWith(".yml")
                || YAML_FILES_WITHOUT_A_YAML_SUFFIX.contains(fileName);
            if (isYaml && PlatformRendererRegistry.mergeShapeFor(service.getKey()) == null) {
                missing.add(service.getKey() + " (" + fileName + ")");
            }
        }
        assertTrue(missing.isEmpty(),
            "these services write a YAML document but declare no PlatformRenderer.mergeShape(), so a "
                + "reactor build will repeat their top-level keys once per module: " + missing);
    }

    /** Non-YAML platforms must not declare a shape — concatenation is right for them. */
    @Test
    void markdownPlatforms_declareNoMergeShape() {
        for (String serviceKey : List.of("claude", "cursor", "windsurf", "copilot", "llms", "locks_report")) {
            assertEquals(null, PlatformRendererRegistry.mergeShapeFor(serviceKey),
                serviceKey + " is not a YAML document and must keep the plain concatenating merge");
        }
    }

    /** A key with no renderer at all must answer "no shape" rather than throw. */
    @Test
    void servicesWithoutARenderer_haveNoShape() {
        assertEquals(null, PlatformRendererRegistry.mergeShapeFor("root_index"));
        assertEquals(null, PlatformRendererRegistry.mergeShapeFor("not-a-service-key"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<Platform> platformsWithAShape() {
        List<Platform> found = new ArrayList<>();
        for (String serviceKey : ALL_SERVICES) {
            Platform platform = Platform.fromServiceKey(serviceKey);
            assertNotNull(platform, serviceKey + " must be a known platform");
            assertNotNull(PlatformRendererRegistry.mergeShapeFor(serviceKey),
                serviceKey + " writes a YAML document and must declare a merge shape");
            found.add(platform);
        }
        return found;
    }

    private static YamlMergeShape shapeOf(Platform platform) {
        YamlMergeShape shape = PlatformRendererRegistry.mergeShapeFor(platform.getServiceKey());
        assertNotNull(shape);
        return shape;
    }

    private static String render(Platform platform, GuardrailModel model) {
        RenderingContext ctx = new RenderingContext(
            "Demo", "# Generated by VibeTags\n", ALL_SERVICES);
        String out = PlatformRendererRegistry.getRenderer(platform).render(model, platform, ctx);
        assertNotNull(out, platform + " rendered nothing");
        return out;
    }

    /** Reuses the model fixture so the two test classes cannot describe different renderings. */
    private static GuardrailModel populatedModel() {
        return MultiModuleYamlValidityTest.modelWith("com.example.contract.Subject");
    }

    private static long wholeLineCount(String rendered, String anchor) {
        return rendered.lines().filter(l -> l.stripTrailing().equals(anchor)).count();
    }

    private static String afterAnchor(String rendered, String anchor) {
        String[] lines = rendered.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripTrailing().equals(anchor)) {
                return String.join("\n", List.of(lines).subList(i + 1, lines.length));
            }
        }
        throw new AssertionError("anchor '" + anchor + "' not found in:\n" + rendered);
    }
}
