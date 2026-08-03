package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole-file formats — the JSON and TOML configs that carry no VibeTags markers — were the
 * second half of issue #265, and they failed twice over in a reactor.
 *
 * <p>First, they never refreshed. {@code generateFiles()} decides whether a shared file may be
 * rewritten from {@code anyContributed}, which asks whether any module's sidecar holds a body for
 * that service — and sidecar bodies were only ever stored for marker-based services. For a JSON or
 * TOML output the answer was permanently "no", so the writer's {@code no-new-rules} guard skipped
 * every update to an existing file. Whatever the first successful write produced stayed there for
 * good: on the four-module {@code example-multimodule}, {@code .mentatconfig.json} and
 * {@code .pr_agent.toml} carried only {@code core}'s guardrails, and every later build logged
 * "no changes".
 *
 * <p>Second, even once they refresh, the content is one module's. There is no marker region to
 * merge into, so the previous code kept the compiling module's own rendering — turning a frozen
 * file into a last-writer-wins file, which is not obviously better.
 *
 * <p>These tests assert what a consuming tool observes: the file parses, and every module's
 * guardrails are in it. {@code .mentatconfig.json} is parsed with a real parser rather than
 * string-matched — a merge that produces invalid JSON would satisfy any {@code contains} check
 * while being unreadable to Mentat.
 */
class MultiModuleWholeFileMergeTest {

    private static final String ALPHA = "com.example.alpha.AlphaService";
    private static final String BETA = "com.example.beta.BetaService";

    /** Services whose file carries no markers and whose renderer emits per-element content. */
    private static final List<String> MERGING_SERVICES = List.of("mentat", "pr_agent");

    /** Services whose file carries no markers but whose renderer output is the same every time. */
    private static final List<String> STATIC_SERVICES = List.of("cody", "qwen_settings", "codex_config");

    // -----------------------------------------------------------------------
    // The merge
    // -----------------------------------------------------------------------

    @Test
    void mentatConfigKeepsEveryModulesGuardrailsAndStaysValidJson() {
        String merged = mergeTwoModules("mentat");

        Object parsed = parseJson(merged);
        String flattened = String.valueOf(parsed);
        assertTrue(flattened.contains(ALPHA),
            "the first module's locked element must survive the merge:\n" + merged);
        assertTrue(flattened.contains(BETA),
            "the second module's locked element must survive the merge:\n" + merged);
    }

    @Test
    void prAgentTomlKeepsEveryModulesGuardrails() {
        String merged = mergeTwoModules("pr_agent");
        assertTrue(merged.contains(ALPHA),
            "the first module's guardrails must survive the merge:\n" + merged);
        assertTrue(merged.contains(BETA),
            "the second module's guardrails must survive the merge:\n" + merged);
    }

    /** Both PR-Agent sections are fed from the same body; a merge must not update only one. */
    @Test
    void prAgentTomlMergesBothInstructionSections() {
        String merged = mergeTwoModules("pr_agent");
        int reviewer = merged.indexOf("[pr_reviewer]");
        int suggestions = merged.indexOf("[pr_code_suggestions]");
        assertTrue(reviewer >= 0 && suggestions > reviewer, "both sections must be present:\n" + merged);

        String reviewerBlock = merged.substring(reviewer, suggestions);
        String suggestionsBlock = merged.substring(suggestions);
        for (String section : List.of("[pr_reviewer]", "[pr_code_suggestions]")) {
            String block = section.equals("[pr_reviewer]") ? reviewerBlock : suggestionsBlock;
            assertTrue(block.contains(ALPHA) && block.contains(BETA),
                section + " is missing a module's guardrails:\n" + block);
        }
    }

    /** A module that contributes nothing must not erase the modules that did. */
    @Test
    void anEmptyModuleDoesNotDisplaceTheContributingOne() {
        for (String service : MERGING_SERVICES) {
            ModuleSidecar alpha = sidecarFor("alpha", service, modelWith(ALPHA));
            ModuleSidecar empty = sidecarFor("empty", service, GuardrailModel.EMPTY);

            String merged = merge(service, List.of(alpha, empty));
            assertTrue(merged.contains(ALPHA), service + ": an empty sibling displaced the contributor");
            if ("mentat".equals(service)) {
                parseJson(merged);
            }
        }
    }

    /** All modules empty is still a document the tool has to be able to read. */
    @Test
    void allModulesEmptyStillProducesAReadableFile() {
        for (String service : MERGING_SERVICES) {
            String merged = merge(service, List.of(
                sidecarFor("a", service, GuardrailModel.EMPTY),
                sidecarFor("b", service, GuardrailModel.EMPTY)));
            assertTrue(!merged.isBlank(), service + ": merged to nothing");
            if ("mentat".equals(service)) {
                parseJson(merged);
            }
        }
    }

    /** A single-module build must be byte-identical to what it produced before any of this. */
    @Test
    void singleModuleOutputIsUnchanged() {
        for (String service : MERGING_SERVICES) {
            String rendered = render(service, modelWith(ALPHA));
            String merged = merge(service, List.of(sidecarFor("only", service, modelWith(ALPHA))));
            assertEquals(rendered, merged, service + ": a lone module's output must not change");
        }
    }

    // -----------------------------------------------------------------------
    // The refresh
    // -----------------------------------------------------------------------

    /**
     * The sidecar has to carry these bodies, because that is what {@code anyContributed} reads to
     * decide whether the shared file may be rewritten at all. Without it the merge above is
     * unreachable: the file is skipped before the content is ever considered.
     */
    @Test
    void sidecarCarriesWholeFileBodiesSoTheFileIsAllowedToRefresh() {
        for (String service : MERGING_SERVICES) {
            ModuleSidecar s = sidecarFor("alpha", service, modelWith(ALPHA));
            assertTrue(s.getBodies().containsKey(service),
                service + ": no sidecar body, so anyContributed stays false and the writer's "
                    + "no-new-rules guard skips every update to an existing file");
        }
        for (String service : STATIC_SERVICES) {
            ModuleSidecar s = sidecarFor("alpha", service, modelWith(ALPHA));
            assertTrue(s.getBodies().containsKey(service),
                service + ": static content still has to refresh — otherwise upgrading VibeTags "
                    + "never updates this file in a reactor");
        }
    }

    /**
     * The rule that stops the next platform repeating this, derived rather than listed: render each
     * marker-free service with an empty model and with a populated one. If the two differ, that
     * file carries per-element content, and in a reactor it therefore needs a merge — otherwise it
     * publishes whichever module compiled last.
     *
     * <p>Deriving it from the renderer's own behaviour means a new JSON or TOML platform is covered
     * the day it is added, with no list here to keep in step.
     */
    @Test
    void everyMarkerFreeServiceThatHasPerElementContentDeclaresAMerge() {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(Path.of("."));
        java.util.List<String> missing = new java.util.ArrayList<>();

        for (Map.Entry<String, Path> entry : serviceFiles.entrySet()) {
            String service = entry.getKey();
            Path file = entry.getValue();
            if (file.getFileName() == null
                || se.deversity.vibetags.processor.internal.GuardrailFileWriter
                       .getMarkersFor(file.getFileName().toString()) != null) {
                continue; // marker files are merged by region, not by this mechanism
            }
            Platform platform = Platform.fromServiceKey(service);
            if (platform == null) {
                continue;
            }
            String empty;
            String populated;
            try {
                empty = PlatformRendererRegistry.getRenderer(platform)
                    .render(GuardrailModel.EMPTY, platform, context());
                populated = PlatformRendererRegistry.getRenderer(platform)
                    .render(modelWith(ALPHA), platform, context());
            } catch (IllegalArgumentException noRenderer) {
                continue; // an opt-in file with no renderer, e.g. root_index
            }
            if (empty == null || populated == null || empty.equals(populated)) {
                continue; // static config: every module renders the same bytes
            }
            if (PlatformRendererRegistry.wholeFileMergeFor(service) == null) {
                missing.add(service + " (" + file.getFileName() + ")");
            }
        }
        assertTrue(missing.isEmpty(),
            "These services write a marker-free file whose content varies with the annotations, but "
                + "declare no PlatformRenderer.wholeFileMerge(). In a reactor each will publish one "
                + "module's view of the project: " + missing);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static RenderingContext context() {
        return new RenderingContext("Demo",
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n",
            Set.of("mentat", "pr_agent", "cody", "qwen_settings", "codex_config", "codex", "qwen"));
    }

    private static String mergeTwoModules(String service) {
        return merge(service, List.of(
            sidecarFor("alpha", service, modelWith(ALPHA)),
            sidecarFor("beta", service, modelWith(BETA))));
    }

    /** Drives the real production path: {@code AIGuardrailProcessor.mergeAcrossModules}. */
    private static String merge(String service, List<ModuleSidecar> sidecars) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(Path.of("."));
        Map<String, String> content = Map.of(service, sidecars.get(0).getBodies().get(service));
        Map<String, String> merged =
            AIGuardrailProcessor.mergeAcrossModules(content, serviceFiles, sidecars);
        String out = merged.get(service);
        assertNotNull(out, service + ": merge produced no content");
        return out;
    }

    private static ModuleSidecar sidecarFor(String moduleId, String service, GuardrailModel model) {
        ModuleSidecar s = new ModuleSidecar(moduleId, moduleId);
        s.putBody(service, render(service, model));
        return s;
    }

    private static String render(String service, GuardrailModel model) {
        Platform platform = Platform.fromServiceKey(service);
        assertNotNull(platform, service + " is not a known platform");
        RenderingContext ctx = new RenderingContext("Demo",
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n",
            Set.of("mentat", "pr_agent", "cody", "qwen_settings", "codex_config"));
        String out = PlatformRendererRegistry.getRenderer(platform).render(model, platform, ctx);
        assertNotNull(out, service + " rendered nothing");
        return out;
    }

    private static GuardrailModel modelWith(String qualifiedName) {
        return MultiModuleYamlValidityTest.modelWith(qualifiedName);
    }

    /**
     * JSON is a subset of YAML 1.2, so the test-scoped SnakeYAML parses {@code .mentatconfig.json}
     * without the processor gaining a JSON dependency. Duplicate keys are rejected: a merge that
     * emitted {@code "locked_files"} twice would be read by a lenient parser as only the last one.
     */
    private static Object parseJson(String json) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            return new Yaml(options).load(json);
        } catch (RuntimeException e) {
            throw new AssertionError("not a valid JSON document: " + e.getMessage()
                + "\n---- content ----\n" + json, e);
        }
    }

    /** Referenced so the unused-import checker keeps these visible in the failure output. */
    @SuppressWarnings("unused")
    private static final Class<?>[] PINNED = {AILocked.class, AIAudit.class};
}
