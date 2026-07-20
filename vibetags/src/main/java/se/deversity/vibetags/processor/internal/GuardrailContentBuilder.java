package se.deversity.vibetags.processor.internal;

import javax.lang.model.element.Element;
import java.util.Map;
import java.util.Set;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * A highly decoupled, thin coordinator that builds AI guardrail files by delegating
 * file rendering to specific PlatformRenderer implementations.
 */
public final class GuardrailContentBuilder {

    private final AnnotationCollector collector;
    private final Set<String> activeServices;
    private final String projectName;
    private final String generatedHeader;

    public GuardrailContentBuilder(AnnotationCollector collector,
                                   Set<String> activeServices,
                                   String projectName,
                                   String generatedHeader) {
        this.collector = collector;
        // Defensive copy: callers must not be able to mutate the active-services set
        // through the reference they passed in.
        this.activeServices = new java.util.LinkedHashSet<>(activeServices);
        this.projectName = projectName;
        this.generatedHeader = generatedHeader;
    }

    /**
     * Result of {@link #build} — service-key → file content, plus per-element granular rule map.
     */
    public static final class Result {
        public final Map<String, String> contentByService;
        public final Map<Element, java.lang.StringBuilder> elementRules;

        Result(Map<String, String> contentByService, Map<Element, java.lang.StringBuilder> elementRules) {
            this.contentByService = contentByService;
            this.elementRules = elementRules;
        }
    }

    public Result build() {
        // Pre-size renderer output buffers from the collected element count: ~160 bytes of rendered
        // content per annotated reference plus a fixed preamble allowance. Avoids repeated
        // grow-and-copy reallocation of the per-platform StringBuilders on large projects.
        int estimatedContentSize = collector.totalAnnotatedReferences() * 160 + 2048;

        // Compute the granular owner set once, before rendering. Aggregate renderers whose granular
        // sibling is active read it from the RenderingContext to emit a scoped-rules index instead
        // of duplicating each element's full guardrails inline. renderGranular depends only on the
        // collector, so ordering it ahead of the per-service loop is safe and avoids a redundant
        // per-element walk inside each renderer.
        boolean granularActive = activeServices.stream().anyMatch(s -> s.endsWith("_granular"));
        Map<Element, java.lang.StringBuilder> elementRules = granularActive
                ? PlatformRendererRegistry.granularRenderer().renderGranular(collector)
                : new java.util.LinkedHashMap<>();

        RenderingContext context = new RenderingContext(projectName, generatedHeader, activeServices,
                estimatedContentSize, elementRules.keySet());
        Map<String, String> contentByService = new java.util.LinkedHashMap<>();

        // Render each active service (excluding granular directories and special-case exclusions)
        for (String serviceKey : activeServices) {
            if (serviceKey.equals("aiexclude")) {
                continue;
            }
            if (serviceKey.endsWith("_granular")) {
                continue;
            }

            Platform platform = Platform.fromServiceKey(serviceKey);
            if (platform != null) {
                String content = PlatformRendererRegistry.getRenderer(platform).render(collector, platform, context);
                if (content != null) {
                    contentByService.put(serviceKey, content);
                }
            }
        }

        // Implicit platform activations for Codex and Qwen configurations
        if (activeServices.contains("codex")) {
            String configContent = PlatformRendererRegistry.getRenderer(Platform.CODEX_CONFIG).render(collector, Platform.CODEX_CONFIG, context);
            if (configContent != null) {
                contentByService.put("codex_config", configContent);
            }
            String rulesContent = PlatformRendererRegistry.getRenderer(Platform.CODEX_RULES).render(collector, Platform.CODEX_RULES, context);
            if (rulesContent != null) {
                contentByService.put("codex_rules", rulesContent);
            }
        }
        if (activeServices.contains("qwen")) {
            String settingsContent = PlatformRendererRegistry.getRenderer(Platform.QWEN_SETTINGS).render(collector, Platform.QWEN_SETTINGS, context);
            if (settingsContent != null) {
                contentByService.put("qwen_settings", settingsContent);
            }
            String refactorContent = PlatformRendererRegistry.getRenderer(Platform.QWEN_REFACTOR).render(collector, Platform.QWEN_REFACTOR, context);
            if (refactorContent != null) {
                contentByService.put("qwen_refactor", refactorContent);
            }
        }
        if (activeServices.contains("cody")) {
            String codyContent = PlatformRendererRegistry.getRenderer(Platform.CODY).render(collector, Platform.CODY, context);
            if (codyContent != null) {
                contentByService.put("cody", codyContent);
            }
        }

        // Special case for AIExclude platform, which has strict activation criteria
        if (activeServices.contains("aiexclude") && (activeServices.contains("gemini") || activeServices.contains("codex"))) {
            Platform p = Platform.AI_EXCLUDE;
            String content = PlatformRendererRegistry.getRenderer(p).render(collector, p, context);
            if (content != null) {
                contentByService.put("aiexclude", content);
            }
        }

        // (granular owner set + elementRules are computed above, before the render loop)

        return new Result(contentByService, elementRules);
    }
}
