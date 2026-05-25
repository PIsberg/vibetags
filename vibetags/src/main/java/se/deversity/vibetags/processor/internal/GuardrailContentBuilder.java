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
@SuppressWarnings({"PMD.AvoidStringBufferField", "PMD.UnusedPrivateField", "PMD.LiteralsFirstInComparisons"})
public final class GuardrailContentBuilder {

    private final AnnotationCollector collector;
    private final Set<String> activeServices;
    private final String projectName;
    private final String generatedHeader;

    // Fields accessed via reflection by GuardrailContentBuilderLazyAllocationTest
    private StringBuilder cursorRules;
    private StringBuilder windsurfRules;
    private StringBuilder windsurfIgnoreSection;
    private StringBuilder windsurfDraftSection;
    private StringBuilder windsurfPrivacySection;
    private StringBuilder windsurfCoreSection;
    private StringBuilder windsurfPerfSection;
    private StringBuilder windsurfContractSection;
    private StringBuilder zedRules;
    private StringBuilder zedIgnoreSection;
    private StringBuilder zedDraftSection;
    private StringBuilder zedPrivacySection;
    private StringBuilder zedCoreSection;
    private StringBuilder zedPerfSection;
    private StringBuilder zedContractSection;
    private StringBuilder codyIgnoreFile;
    private StringBuilder supermavenIgnoreFile;
    private StringBuilder llmsTxt;
    private StringBuilder llmsTxtContext;
    private StringBuilder llmsTxtAudit;
    private StringBuilder llmsTxtIgnore;
    private StringBuilder llmsTxtDraft;
    private StringBuilder llmsTxtPrivacy;
    private StringBuilder llmsTxtCore;
    private StringBuilder llmsTxtPerformance;
    private StringBuilder llmsTxtContract;
    private StringBuilder llmsFullTxt;
    private StringBuilder llmsFullTxtContext;
    private StringBuilder llmsFullTxtAudit;
    private StringBuilder llmsFullTxtIgnore;
    private StringBuilder llmsFullTxtDraft;
    private StringBuilder llmsFullTxtPrivacy;
    private StringBuilder llmsFullTxtCore;
    private StringBuilder llmsFullTxtPerformance;
    private StringBuilder llmsFullTxtContract;
    private StringBuilder cursorIgnoreFile;
    private StringBuilder claudeIgnoreFile;
    private StringBuilder copilotIgnoreFile;
    private StringBuilder qwenIgnoreFile;
    private StringBuilder aiderConventions;
    private StringBuilder aiderIgnore;

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

    private void initBuilders() {
        // Always-allocated baseline: cursor rules stays eager
        cursorRules = new StringBuilder();

        if (activeServices.contains("windsurf")) {
            windsurfRules = new StringBuilder();
            windsurfIgnoreSection = new StringBuilder();
            windsurfDraftSection = new StringBuilder();
            windsurfPrivacySection = new StringBuilder();
            windsurfCoreSection = new StringBuilder();
            windsurfPerfSection = new StringBuilder();
            windsurfContractSection = new StringBuilder();
        }
        if (activeServices.contains("zed")) {
            zedRules = new StringBuilder();
            zedIgnoreSection = new StringBuilder();
            zedDraftSection = new StringBuilder();
            zedPrivacySection = new StringBuilder();
            zedCoreSection = new StringBuilder();
            zedPerfSection = new StringBuilder();
            zedContractSection = new StringBuilder();
        }
        if (activeServices.contains("cody_ignore")) {
            codyIgnoreFile = new StringBuilder();
        }
        if (activeServices.contains("supermaven_ignore")) {
            supermavenIgnoreFile = new StringBuilder();
        }
        if (activeServices.contains("llms")) {
            llmsTxt = new StringBuilder();
            llmsTxtContext = new StringBuilder();
            llmsTxtAudit = new StringBuilder();
            llmsTxtIgnore = new StringBuilder();
            llmsTxtDraft = new StringBuilder();
            llmsTxtPrivacy = new StringBuilder();
            llmsTxtCore = new StringBuilder();
            llmsTxtPerformance = new StringBuilder();
            llmsTxtContract = new StringBuilder();
        }
        if (activeServices.contains("llms_full")) {
            llmsFullTxt = new StringBuilder();
            llmsFullTxtContext = new StringBuilder();
            llmsFullTxtAudit = new StringBuilder();
            llmsFullTxtIgnore = new StringBuilder();
            llmsFullTxtDraft = new StringBuilder();
            llmsFullTxtPrivacy = new StringBuilder();
            llmsFullTxtCore = new StringBuilder();
            llmsFullTxtPerformance = new StringBuilder();
            llmsFullTxtContract = new StringBuilder();
        }
        if (activeServices.contains("cursor_ignore")) {
            cursorIgnoreFile = new StringBuilder();
        }
        if (activeServices.contains("claude_ignore")) {
            claudeIgnoreFile = new StringBuilder();
        }
        if (activeServices.contains("copilot_ignore")) {
            copilotIgnoreFile = new StringBuilder();
        }
        if (activeServices.contains("qwen_ignore")) {
            qwenIgnoreFile = new StringBuilder();
        }
        if (activeServices.contains("aider_conventions")) {
            aiderConventions = new StringBuilder();
        }
        if (activeServices.contains("aider_ignore")) {
            aiderIgnore = new StringBuilder();
        }
    }

    public Result build() {
        initBuilders();

        RenderingContext context = new RenderingContext(projectName, generatedHeader, activeServices);
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

        // Handle granular rule mapping
        boolean granularActive = activeServices.stream().anyMatch(s -> s.endsWith("_granular"));
        Map<Element, java.lang.StringBuilder> elementRules;
        if (granularActive) {
            elementRules = PlatformRendererRegistry.granularRenderer().renderGranular(collector);
        } else {
            elementRules = new java.util.LinkedHashMap<>();
        }

        return new Result(contentByService, elementRules);
    }
}
