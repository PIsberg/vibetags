package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AISecure;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the content of every guardrail file from the annotations collected this round.
 * Owns all per-platform {@link StringBuilder}s; produces a service-key → content map plus the
 * per-element granular rule map (consumed by {@code GranularRulesWriter}).
 *
 * <p>The class has no IO: it neither reads nor writes files. Construct, call {@link #build},
 * use the result.
 */
@SuppressWarnings({"PMD.AvoidStringBufferField", "PMD.AvoidDuplicateLiterals"})
public final class GuardrailContentBuilder {

    private final AnnotationCollector collector;
    private final java.util.Set<String> activeServices;
    private final String projectName;
    private final String generatedHeader;

    // Active-service flag cache (read once, used in tight per-element loops)
    private final boolean cursorActive;
    private final boolean claudeActive;
    private final boolean aiexcludeActive;
    private final boolean codexActive;
    private final boolean copilotActive;
    private final boolean qwenActive;
    private final boolean geminiActive;
    private final boolean llmsActive;
    private final boolean llmsFullActive;
    private final boolean aiderConvActive;
    private final boolean aiderIgnoreActive;
    private final boolean cursorIgnoreFileActive;
    private final boolean claudeIgnoreFileActive;
    private final boolean copilotIgnoreFileActive;
    private final boolean qwenIgnoreFileActive;
    private final boolean granularActive;

    // v0.7.0 platforms
    private final boolean windsurfActive;
    private final boolean zedActive;
    private final boolean codyIgnoreActive;
    private final boolean supermavenIgnoreActive;

    // v0.8.0 platforms
    private final boolean mentatActive;
    private final boolean sweepActive;
    private final boolean plandexActive;
    private final boolean doubleIgnoreActive;
    private final boolean interpreterActive;
    private final boolean codeiumIgnoreActive;

    // v0.9.6 platforms
    private final boolean geminiMdActive;
    private final boolean antigravityIgnoreActive;

    // v0.9.7 platforms
    private final boolean clineActive;
    private final boolean junieActive;
    /** True if either gemini_instructions.md or GEMINI.md is active — drives gemini section builders. */
    private final boolean anyGeminiActive;

    // Windsurf test-driven section
    private StringBuilder windsurfTestDrivenSection;

    // Zed test-driven section
    private StringBuilder zedTestDrivenSection;

    // llms.txt test-driven sections
    private StringBuilder llmsTxtTestDriven;
    private StringBuilder llmsFullTxtTestDriven;

    // llms.txt sections for v0.9.5 annotations
    private StringBuilder llmsTxtThreadSafe;
    private StringBuilder llmsTxtImmutable;
    private StringBuilder llmsTxtDeprecated;
    private StringBuilder llmsTxtObservability;
    private StringBuilder llmsTxtRegulation;
    private StringBuilder llmsFullTxtThreadSafe;
    private StringBuilder llmsFullTxtImmutable;
    private StringBuilder llmsFullTxtDeprecated;
    private StringBuilder llmsFullTxtObservability;
    private StringBuilder llmsFullTxtRegulation;

    private StringBuilder llmsTxtParallelTests;
    private StringBuilder llmsTxtLegacyBridge;
    private StringBuilder llmsTxtArchitecture;
    private StringBuilder llmsTxtPublicApi;
    private StringBuilder llmsTxtStrictExceptions;
    private StringBuilder llmsTxtStrictTypes;
    private StringBuilder llmsTxtInternationalized;
    private StringBuilder llmsTxtStrictClasspath;
    private StringBuilder llmsTxtSchemaSafe;

    private StringBuilder llmsFullTxtParallelTests;
    private StringBuilder llmsFullTxtLegacyBridge;
    private StringBuilder llmsFullTxtArchitecture;
    private StringBuilder llmsFullTxtPublicApi;
    private StringBuilder llmsFullTxtStrictExceptions;
    private StringBuilder llmsFullTxtStrictTypes;
    private StringBuilder llmsFullTxtInternationalized;
    private StringBuilder llmsFullTxtStrictClasspath;
    private StringBuilder llmsFullTxtSchemaSafe;

    // v1.0.0 annotations
    private StringBuilder llmsTxtIdempotent;
    private StringBuilder llmsFullTxtIdempotent;
    private StringBuilder llmsTxtFeatureFlag;
    private StringBuilder llmsFullTxtFeatureFlag;
    private StringBuilder llmsTxtSecure;
    private StringBuilder llmsFullTxtSecure;

    // Windsurf builders
    private StringBuilder windsurfRules;
    private StringBuilder windsurfIgnoreSection;
    private StringBuilder windsurfDraftSection;
    private StringBuilder windsurfPrivacySection;
    private StringBuilder windsurfCoreSection;
    private StringBuilder windsurfPerfSection;
    private StringBuilder windsurfContractSection;

    // Zed builders
    private StringBuilder zedRules;
    private StringBuilder zedIgnoreSection;
    private StringBuilder zedDraftSection;
    private StringBuilder zedPrivacySection;
    private StringBuilder zedCoreSection;
    private StringBuilder zedPerfSection;
    private StringBuilder zedContractSection;

    // Other new platform builders
    private StringBuilder codyIgnoreFile;
    private StringBuilder supermavenIgnoreFile;


    // v0.8.0 platform builders
    private StringBuilder mentatLocked;
    private StringBuilder mentatAudit;
    private StringBuilder mentatPrivacy;
    private StringBuilder mentatCore;
    private StringBuilder mentatPerf;
    private StringBuilder mentatContract;
    private StringBuilder mentatIgnore;
    private StringBuilder mentatDraft;
    private StringBuilder mentatTestDriven;

    private StringBuilder sweepRules;
    private StringBuilder plandexLocked;
    private StringBuilder plandexAudit;
    private StringBuilder plandexPrivacy;
    private StringBuilder interpreterRules;
    private StringBuilder doubleIgnoreFile;
    private StringBuilder codeiumIgnoreFile;
    private StringBuilder antigravityIgnoreFile;

    // Primary platform builders
    private StringBuilder cursorRules;
    private StringBuilder claudeMd;
    private StringBuilder aiExclude;
    private StringBuilder codexAgents;
    private StringBuilder copilot;
    private StringBuilder qwenMd;
    private StringBuilder cursorIgnoreSection;
    private StringBuilder cursorIgnoreFile;
    private StringBuilder claudeIgnoreFile;
    private StringBuilder copilotIgnoreFile;
    private StringBuilder qwenIgnoreFile;
    private StringBuilder aiderConventions;
    private StringBuilder aiderIgnore;

    // llms.txt / llms-full.txt main + per-section builders
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

    // Per-element granular rule sections (one StringBuilder per owning class/package)
    private final Map<Element, StringBuilder> elementRules = new LinkedHashMap<>();

    public GuardrailContentBuilder(AnnotationCollector collector,
                                   java.util.Set<String> activeServices,
                                   String projectName,
                                   String generatedHeader) {
        this.collector = collector;
        this.activeServices = activeServices;
        this.projectName = projectName;
        this.generatedHeader = generatedHeader;

        this.cursorActive            = activeServices.contains("cursor");
        this.claudeActive            = activeServices.contains("claude");
        this.aiexcludeActive         = activeServices.contains("aiexclude");
        this.codexActive             = activeServices.contains("codex");
        this.copilotActive           = activeServices.contains("copilot");
        this.qwenActive              = activeServices.contains("qwen");
        this.geminiActive            = activeServices.contains("gemini");
        this.llmsActive              = activeServices.contains("llms");
        this.llmsFullActive          = activeServices.contains("llms_full");
        this.aiderConvActive         = activeServices.contains("aider_conventions");
        this.aiderIgnoreActive       = activeServices.contains("aider_ignore");
        this.cursorIgnoreFileActive  = activeServices.contains("cursor_ignore");
        this.claudeIgnoreFileActive  = activeServices.contains("claude_ignore");
        this.copilotIgnoreFileActive = activeServices.contains("copilot_ignore");
        this.qwenIgnoreFileActive    = activeServices.contains("qwen_ignore");
        this.granularActive          = activeServices.contains("cursor_granular")
                                    || activeServices.contains("trae_granular")
                                    || activeServices.contains("roo_granular")
                                    || activeServices.contains("windsurf_granular")
                                    || activeServices.contains("continue_granular")
                                    || activeServices.contains("tabnine_granular")
                                    || activeServices.contains("amazonq_granular")
                                    || activeServices.contains("ai_rules_granular")
                                    || activeServices.contains("pearai_granular")
                                    || activeServices.contains("kiro_granular");
        this.windsurfActive          = activeServices.contains("windsurf");
        this.zedActive               = activeServices.contains("zed");
        this.codyIgnoreActive        = activeServices.contains("cody_ignore");
        this.supermavenIgnoreActive  = activeServices.contains("supermaven_ignore");
        this.mentatActive            = activeServices.contains("mentat");
        this.sweepActive             = activeServices.contains("sweep");
        this.plandexActive           = activeServices.contains("plandex");
        this.doubleIgnoreActive      = activeServices.contains("double_ignore");
        this.interpreterActive       = activeServices.contains("interpreter");
        this.codeiumIgnoreActive     = activeServices.contains("codeium_ignore");
        this.geminiMdActive          = activeServices.contains("gemini_md");
        this.antigravityIgnoreActive = activeServices.contains("antigravity_ignore");
        this.anyGeminiActive         = this.geminiActive || this.geminiMdActive;
        this.clineActive             = activeServices.contains("cline");
        this.junieActive             = activeServices.contains("junie");
    }

    /**
     * Pre-allocates a {@link StringBuilder} with at least {@code hint} characters of capacity,
     * then appends {@code header}. Sizes the buffer to (estimated) final content size so the
     * eight per-annotation appendXxx() loops don't trigger log₂(N) char[] grow-and-copy passes.
     *
     * <p>Over-allocating by 30-50% is fine — char[] heap pressure is bounded by activeServices
     * and ~15 builders per platform; under-allocating costs one System.arraycopy on the next grow.
     */
    private static StringBuilder preSized(String header, int hint) {
        StringBuilder sb = new StringBuilder(Math.max(hint, header.length() + 32));
        sb.append(header);
        return sb;
    }

    /**
     * Heuristic for main per-platform builder size: each annotated element contributes ~1500 chars
     * across all sections (locked, context, audit, draft, privacy, core, performance, contract).
     * Floor of 4 KB so empty/small projects don't waste cycles on tiny grows; cap of 256 KB keeps
     * per-builder heap pressure bounded on huge codebases (one grow above the cap is cheaper than
     * pre-allocating megabytes per platform × ~12 active platforms).
     */
    private int mainBuilderHint() {
        int n = collector.locked().size() + collector.context().size() + collector.audit().size()
              + collector.draft().size() + collector.privacy().size() + collector.core().size()
              + collector.performance().size() + collector.contract().size() + collector.ignore().size()
              + collector.testDriven().size() + collector.threadSafe().size() + collector.immutable().size()
              + collector.deprecated().size() + collector.observability().size() + collector.regulation().size()
              + collector.parallelTests().size() + collector.legacyBridge().size() + collector.architecture().size()
              + collector.publicApi().size() + collector.strictExceptions().size() + collector.strictTypes().size()
              + collector.internationalized().size() + collector.strictClasspath().size() + collector.schemaSafe().size()
              + collector.idempotent().size() + collector.featureFlag().size() + collector.secure().size();
        return Math.max(4096, Math.min(256 * 1024, 1500 * n));
    }

    /** Section-builder size estimate: each element of that type contributes ~256 chars. */
    private static int sectionHint(int countOfType) {
        return Math.max(256, 256 * countOfType);
    }

    /** Result of {@link #build} — service-key → file content, plus per-element granular rule map. */
    public static final class Result {
        public final Map<String, String> contentByService;
        public final Map<Element, StringBuilder> elementRules;

        Result(Map<String, String> contentByService, Map<Element, StringBuilder> elementRules) {
            this.contentByService = contentByService;
            this.elementRules = elementRules;
        }
    }

    public Result build() {
        initBuilders();

        // Per-platform Gemini section builders (assembled at the end)
        StringBuilder geminiLocked = new StringBuilder();
        StringBuilder geminiContext = new StringBuilder();

        StringBuilder cursorParallelTestsSec = new StringBuilder("\n## 🧪 STRICT TEST ISOLATION\nThe following elements must be strictly isolated when generating or modifying tests. No shared mutable state or resource conflicts are permitted.\n\n");
        StringBuilder claudeParallelTestsSec = new StringBuilder("  <test_isolation_elements>\n");
        StringBuilder codexParallelTestsSec = new StringBuilder("\n## 🧪 STRICT TEST ISOLATION\nTests for the following elements must be strictly isolated (no shared mutable state/resources):\n\n");
        StringBuilder copilotParallelTestsSec = new StringBuilder("\n## Strict Test Isolation\nDo not share mutable state or external resources in tests for these elements:\n\n");
        StringBuilder qwenParallelTestsSec = new StringBuilder("\n## 🧪 STRICT TEST ISOLATION\nTests must run in complete thread isolation without shared mutable state:\n\n");
        StringBuilder geminiParallelTestsSec = new StringBuilder();
        StringBuilder windsurfParallelTestsSec = new StringBuilder("\n## 🧪 STRICT TEST ISOLATION\nEnforce strict isolation for tests generated or modified for these elements:\n\n");
        StringBuilder zedParallelTestsSec = new StringBuilder("\n## Strict Test Isolation\nEnforce strict isolation in tests for these elements:\n\n");

        StringBuilder cursorLegacyBridgeSec = new StringBuilder("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nThe following elements are legacy compatibility bridges. Do not attempt to modernize or refactor their structural patterns; only modify internal business logic as explicitly requested.\n\n");
        StringBuilder claudeLegacyBridgeSec = new StringBuilder("  <legacy_bridge_elements>\n");
        StringBuilder codexLegacyBridgeSec = new StringBuilder("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not restructure or refactor structural patterns of these compatibility bridges:\n\n");
        StringBuilder copilotLegacyBridgeSec = new StringBuilder("\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
        StringBuilder qwenLegacyBridgeSec = new StringBuilder("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nModernization/structural refactoring is prohibited for these elements:\n\n");
        StringBuilder geminiLegacyBridgeSec = new StringBuilder();
        StringBuilder windsurfLegacyBridgeSec = new StringBuilder("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
        StringBuilder zedLegacyBridgeSec = new StringBuilder("\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n");

        StringBuilder cursorArchitectureSec = new StringBuilder("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nThe following elements have strict layering constraints. Prohibit imports or references that cross boundaries.\n\n");
        StringBuilder claudeArchitectureSec = new StringBuilder("  <architecture_elements>\n");
        StringBuilder codexArchitectureSec = new StringBuilder("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layering must be respected. No illegal boundary crossing references:\n\n");
        StringBuilder copilotArchitectureSec = new StringBuilder("\n## Architectural Boundary Constraints\nStrict layering must be respected. Boundary crossing references are prohibited:\n\n");
        StringBuilder qwenArchitectureSec = new StringBuilder("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layered architecture constraints apply to these elements:\n\n");
        StringBuilder geminiArchitectureSec = new StringBuilder();
        StringBuilder windsurfArchitectureSec = new StringBuilder("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layered architecture constraints apply to these elements:\n\n");
        StringBuilder zedArchitectureSec = new StringBuilder("\n## Architectural Constraints\nStrict layered architecture constraints apply to these elements:\n\n");

        StringBuilder cursorPublicApiSec = new StringBuilder("\n## 🔌 PUBLIC API SURFACE PROTECTION\nThe following elements are public-facing API surfaces. Always preserve public signatures, Javadoc, and backwards compatibility.\n\n");
        StringBuilder claudePublicApiSec = new StringBuilder("  <public_api_elements>\n");
        StringBuilder codexPublicApiSec = new StringBuilder("\n## 🔌 PUBLIC API SURFACE PROTECTION\nPreserve public signatures, Javadoc, and behavior without breaking backwards compatibility:\n\n");
        StringBuilder copilotPublicApiSec = new StringBuilder("\n## Public API Surface Protection\nDo not modify public signatures or break compatibility for these elements:\n\n");
        StringBuilder qwenPublicApiSec = new StringBuilder("\n## 🔌 PUBLIC API SURFACE PROTECTION\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
        StringBuilder geminiPublicApiSec = new StringBuilder();
        StringBuilder windsurfPublicApiSec = new StringBuilder("\n## 🔌 PUBLIC API SURFACE PROTECTION\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
        StringBuilder zedPublicApiSec = new StringBuilder("\n## Public API Protection\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");

        StringBuilder cursorStrictExceptionsSec = new StringBuilder("\n## 🚨 STRICT EXCEPTION HANDLING\nThe following elements have strict exception constraints. Prohibit catching or throwing generic Exception/Throwable.\n\n");
        StringBuilder claudeStrictExceptionsSec = new StringBuilder("  <strict_exceptions_elements>\n");
        StringBuilder codexStrictExceptionsSec = new StringBuilder("\n## 🚨 STRICT EXCEPTION HANDLING\nPrecise and robust exception handling required. Generic exception catching/throwing is prohibited:\n\n");
        StringBuilder copilotStrictExceptionsSec = new StringBuilder("\n## Strict Exception Handling\nPrecise exception handling required. Do not catch or throw generic Exception/Throwable:\n\n");
        StringBuilder qwenStrictExceptionsSec = new StringBuilder("\n## 🚨 STRICT EXCEPTION HANDLING\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
        StringBuilder geminiStrictExceptionsSec = new StringBuilder();
        StringBuilder windsurfStrictExceptionsSec = new StringBuilder("\n## 🚨 STRICT EXCEPTION HANDLING\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
        StringBuilder zedStrictExceptionsSec = new StringBuilder("\n## Strict Exceptions\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");

        StringBuilder cursorStrictTypesSec = new StringBuilder("\n## 🏷️ STRICT TYPE SAFETY\nThe following elements prohibit loose typing such as Object or Map<String, Object>. Strong type safety is required.\n\n");
        StringBuilder claudeStrictTypesSec = new StringBuilder("  <strict_types_elements>\n");
        StringBuilder codexStrictTypesSec = new StringBuilder("\n## 🏷️ STRICT TYPE SAFETY\nType safety must be strictly preserved. Loose or erased types are prohibited:\n\n");
        StringBuilder copilotStrictTypesSec = new StringBuilder("\n## Strict Type Safety\nLoose typing is prohibited. Strongly-typed objects must be used:\n\n");
        StringBuilder qwenStrictTypesSec = new StringBuilder("\n## 🏷️ STRICT TYPE SAFETY\nLoose typing is strictly prohibited for these elements:\n\n");
        StringBuilder geminiStrictTypesSec = new StringBuilder();
        StringBuilder windsurfStrictTypesSec = new StringBuilder("\n## 🏷️ STRICT TYPE SAFETY\nLoose typing is strictly prohibited for these elements:\n\n");
        StringBuilder zedStrictTypesSec = new StringBuilder("\n## Strict Types\nLoose typing is strictly prohibited for these elements:\n\n");

        StringBuilder cursorInternationalizedSec = new StringBuilder("\n## 🌐 INTERNATIONALIZATION MANDATE\nThe following elements implement i18n requirements. Prohibit hardcoded user-facing strings.\n\n");
        StringBuilder claudeInternationalizedSec = new StringBuilder("  <internationalized_elements>\n");
        StringBuilder codexInternationalizedSec = new StringBuilder("\n## 🌐 INTERNATIONALIZATION MANDATE\nDo not hardcode user-facing strings. All user-visible text must be localized:\n\n");
        StringBuilder copilotInternationalizedSec = new StringBuilder("\n## Internationalization Mandate\nAll user-visible text must be localized. Do not hardcode strings:\n\n");
        StringBuilder qwenInternationalizedSec = new StringBuilder("\n## 🌐 INTERNATIONALIZATION MANDATE\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
        StringBuilder geminiInternationalizedSec = new StringBuilder();
        StringBuilder windsurfInternationalizedSec = new StringBuilder("\n## 🌐 INTERNATIONALIZATION MANDATE\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
        StringBuilder zedInternationalizedSec = new StringBuilder("\n## Internationalization\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");

        StringBuilder cursorStrictClasspathSec = new StringBuilder("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nThe following elements prohibit dynamic runtime class loading, reflections, or loading of unverified dynamic code.\n\n");
        StringBuilder claudeStrictClasspathSec = new StringBuilder("  <strict_classpath_elements>\n");
        StringBuilder codexStrictClasspathSec = new StringBuilder("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic class loading, custom classloaders, and reflection hacks are prohibited:\n\n");
        StringBuilder copilotStrictClasspathSec = new StringBuilder("\n## Strict Classpath Integrity\nDynamic class loading and reflection hacks are strictly prohibited:\n\n");
        StringBuilder qwenStrictClasspathSec = new StringBuilder("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
        StringBuilder geminiStrictClasspathSec = new StringBuilder();
        StringBuilder windsurfStrictClasspathSec = new StringBuilder("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
        StringBuilder zedStrictClasspathSec = new StringBuilder("\n## Strict Classpath\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");

        StringBuilder cursorSchemaSafeSec = new StringBuilder("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nThe following elements have schema safety constraints. Restrict changing formats/fields without a backward-compatible migration plan.\n\n");
        StringBuilder claudeSchemaSafeSec = new StringBuilder("  <schema_safe_elements>\n");
        StringBuilder codexSchemaSafeSec = new StringBuilder("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nPreserve database/contract schema and serialization compatibility on every change:\n\n");
        StringBuilder copilotSchemaSafeSec = new StringBuilder("\n## Schema & Serialization Safety\nDo not change serialization formats or schemas without a backward-compatible migration plan:\n\n");
        StringBuilder qwenSchemaSafeSec = new StringBuilder("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
        StringBuilder geminiSchemaSafeSec = new StringBuilder();
        StringBuilder windsurfSchemaSafeSec = new StringBuilder("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
        StringBuilder zedSchemaSafeSec = new StringBuilder("\n## Schema Safety\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");

        for (Element e : collector.locked())      appendLocked(e, geminiLocked);
        appendContextHeaders();
        for (Element e : collector.context())     appendContext(e, geminiContext);
        claudeMd.append("  </contextual_instructions>\n");

        StringBuilder claudeIgnoreSec = new StringBuilder("  <ignored_elements>\n");
        StringBuilder codexIgnoreSec  = new StringBuilder("\n## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI context and completions:\n\n");
        StringBuilder geminiIgnoreSec = new StringBuilder("## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI context and completions:\n\n");
        StringBuilder copilotIgnoreSec = new StringBuilder("\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n");
        StringBuilder qwenIgnoreSec   = new StringBuilder("\n## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI's memory and context:\n\n");
        for (Element e : collector.ignore())
            appendIgnore(e, claudeIgnoreSec, codexIgnoreSec, geminiIgnoreSec, copilotIgnoreSec, qwenIgnoreSec);

        StringBuilder cursorAuditSec   = new StringBuilder("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\n\n");
        StringBuilder claudeAuditSec   = new StringBuilder("\n  <audit_requirements>\n");
        StringBuilder geminiMd         = new StringBuilder("# GEMINI AI INSTRUCTIONS\n" + generatedHeader + "\n");
        StringBuilder geminiAuditSec   = new StringBuilder();
        StringBuilder codexAuditSec    = new StringBuilder("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\n\n");
        StringBuilder copilotAuditSec  = new StringBuilder("\n## Security Audit Requirements\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n");
        StringBuilder qwenAuditSec     = new StringBuilder("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review. Explicitly state that you have audited the changes for the listed vulnerabilities.\n\n");
        StringBuilder windsurfAuditSec = new StringBuilder("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code.\n\n");
        StringBuilder zedAuditSec      = new StringBuilder("\n## Security Audits\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n");
        for (Element e : collector.audit())
            appendAudit(e, cursorAuditSec, claudeAuditSec, geminiAuditSec, codexAuditSec, copilotAuditSec, qwenAuditSec, windsurfAuditSec, zedAuditSec);

        StringBuilder cursorDraftSec = new StringBuilder("\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n");
        StringBuilder claudeDraftSec = new StringBuilder("  <implementation_tasks>\n");
        StringBuilder codexDraftSec  = new StringBuilder("\n## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
        StringBuilder geminiDraftSec = new StringBuilder("## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
        StringBuilder copilotDraftSec = new StringBuilder("\n## Implementation Tasks\nFollow these instructions to implement the drafts:\n\n");
        StringBuilder qwenDraftSec   = new StringBuilder("\n## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
        for (Element e : collector.draft())
            appendDraft(e, cursorDraftSec, claudeDraftSec, codexDraftSec, geminiDraftSec, copilotDraftSec, qwenDraftSec);

        StringBuilder cursorPrivacySec = new StringBuilder("\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNEVER include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n");
        StringBuilder claudePrivacySec = new StringBuilder("  <pii_guardrails>\n");
        StringBuilder codexPrivacySec  = new StringBuilder("\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle PII. Never include their runtime values in logs,\nconsole output, external API calls, test fixtures, or mock data.\n\n");
        StringBuilder geminiPrivacySec = new StringBuilder("## PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNever include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n");
        StringBuilder copilotPrivacySec = new StringBuilder("\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs the runtime values of these elements:\n\n");
        StringBuilder qwenPrivacySec   = new StringBuilder("\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle PII. Never include their runtime values in logs,\nconsole output, external API calls, test fixtures, or mock data.\n\n");
        for (Element e : collector.privacy())
            appendPrivacy(e, cursorPrivacySec, claudePrivacySec, codexPrivacySec, geminiPrivacySec, copilotPrivacySec, qwenPrivacySec);

        StringBuilder cursorCoreSec = new StringBuilder("\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
        StringBuilder claudeCoreSec = new StringBuilder("  <core_elements>\n");
        StringBuilder codexCoreSec  = new StringBuilder("\n## 🧠 CORE FUNCTIONALITY\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
        StringBuilder copilotCoreSec = new StringBuilder("\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n");
        StringBuilder qwenCoreSec   = new StringBuilder("\n## 🧠 CORE FUNCTIONALITY\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
        StringBuilder geminiCoreSec = new StringBuilder();
        for (Element e : collector.core())
            appendCore(e, cursorCoreSec, claudeCoreSec, codexCoreSec, copilotCoreSec, qwenCoreSec, geminiCoreSec);

        StringBuilder cursorPerfSec = new StringBuilder("\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nThe following elements are on a hot path. Never introduce O(n²) complexity. Always reason about time/space before proposing changes.\n\n");
        StringBuilder claudePerfSec = new StringBuilder("  <performance_constraints>\n");
        StringBuilder codexPerfSec  = new StringBuilder("\n## ⚡ PERFORMANCE CONSTRAINTS\nHot-path elements — never introduce O(n²) or worse. Always reason about complexity before proposing changes.\n\n");
        StringBuilder copilotPerfSec = new StringBuilder("\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n");
        StringBuilder qwenPerfSec   = new StringBuilder("\n## ⚡ PERFORMANCE CONSTRAINTS\nHot-path elements — O(n²) complexity is forbidden. Reason about complexity before proposing changes.\n\n");
        StringBuilder geminiPerfSec = new StringBuilder();
        for (Element e : collector.performance())
            appendPerformance(e, cursorPerfSec, claudePerfSec, codexPerfSec, copilotPerfSec, qwenPerfSec, geminiPerfSec);

        StringBuilder cursorContractSec = new StringBuilder("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nThe following elements have contract-frozen public signatures. You MAY change internal implementation logic, but MUST NOT modify method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
        StringBuilder claudeContractSec = new StringBuilder("  <contract_signatures>\n");
        StringBuilder codexContractSec  = new StringBuilder("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal logic may be modified, but never change method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
        StringBuilder copilotContractSec = new StringBuilder("\n## Contract-Frozen Signatures\nDo not modify the public signatures of the following elements. Internal implementation changes are permitted:\n\n");
        StringBuilder qwenContractSec   = new StringBuilder("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nSignatures (method names, parameters, return types, checked exceptions) are immutable. Internal logic may be changed.\n\n");
        StringBuilder geminiContractSec = new StringBuilder();
        for (Element e : collector.contract())
            appendContract(e, cursorContractSec, claudeContractSec, codexContractSec, copilotContractSec, qwenContractSec, geminiContractSec);

        StringBuilder cursorTestDrivenSec = new StringBuilder("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nThe following elements require a corresponding test update whenever their logic is modified.\nAI MUST NOT propose changes to these elements without also providing the matching test code.\n\n");
        StringBuilder claudeTestDrivenSec = new StringBuilder("  <test_driven_requirements>\n");
        StringBuilder codexTestDrivenSec  = new StringBuilder("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by a matching test update in the same response.\n\n");
        StringBuilder copilotTestDrivenSec = new StringBuilder("\n## Test-Driven Requirements\nDo not suggest changes to the following elements without also providing the corresponding test update:\n\n");
        StringBuilder qwenTestDrivenSec   = new StringBuilder("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by a matching test update in the same response.\n\n");
        StringBuilder geminiTestDrivenSec = new StringBuilder();
        for (Element e : collector.testDriven())
            appendTestDriven(e, cursorTestDrivenSec, claudeTestDrivenSec, codexTestDrivenSec, copilotTestDrivenSec, qwenTestDrivenSec, geminiTestDrivenSec);

        StringBuilder cursorThreadSafeSec = new StringBuilder("\n## 🧵 THREAD-SAFE BY DESIGN\nThe following elements are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning.\n\n");
        StringBuilder claudeThreadSafeSec = new StringBuilder("  <thread_safe_elements>\n");
        StringBuilder codexThreadSafeSec  = new StringBuilder("\n## 🧵 THREAD-SAFE BY DESIGN\nThese elements are explicitly designed to be thread-safe. Preserve the synchronization invariant on every change.\n\n");
        StringBuilder copilotThreadSafeSec = new StringBuilder("\n## Thread-Safe by Design\nDo not modify these elements without preserving their thread-safety strategy:\n\n");
        StringBuilder qwenThreadSafeSec   = new StringBuilder("\n## 🧵 THREAD-SAFE BY DESIGN\nThese elements are explicitly designed to be thread-safe — preserve the synchronization invariant.\n\n");
        StringBuilder geminiThreadSafeSec = new StringBuilder();
        for (Element e : collector.threadSafe())
            appendThreadSafe(e, cursorThreadSafeSec, claudeThreadSafeSec, codexThreadSafeSec, copilotThreadSafeSec, qwenThreadSafeSec, geminiThreadSafeSec);

        StringBuilder cursorImmutableSec = new StringBuilder("\n## ❄️ IMMUTABLE TYPES\nThe following types are declared immutable. NEVER introduce non-final fields, setters, or mutating methods.\n\n");
        StringBuilder claudeImmutableSec = new StringBuilder("  <immutable_types>\n");
        StringBuilder codexImmutableSec  = new StringBuilder("\n## ❄️ IMMUTABLE TYPES\nThe following types are immutable. Do not introduce non-final fields, setters, or mutating methods.\n\n");
        StringBuilder copilotImmutableSec = new StringBuilder("\n## Immutable Types\nThe following types are immutable. Do not add mutating methods or non-final fields:\n\n");
        StringBuilder qwenImmutableSec   = new StringBuilder("\n## ❄️ IMMUTABLE TYPES\nThe following types are immutable — never add setters, mutating methods, or non-final fields.\n\n");
        StringBuilder geminiImmutableSec = new StringBuilder();
        for (Element e : collector.immutable())
            appendImmutable(e, cursorImmutableSec, claudeImmutableSec, codexImmutableSec, copilotImmutableSec, qwenImmutableSec, geminiImmutableSec);

        StringBuilder cursorDeprecatedSec = new StringBuilder("\n## ⚠️ DEPRECATED — ROUTE CALLERS AWAY\nThe following elements are deprecated. Do not extend them. Suggest migrating any caller to the named replacement.\n\n");
        StringBuilder claudeDeprecatedSec = new StringBuilder("  <deprecated_elements>\n");
        StringBuilder codexDeprecatedSec  = new StringBuilder("\n## ⚠️ DEPRECATED ELEMENTS\nDo not extend these elements. Suggest migrating callers to the listed replacement.\n\n");
        StringBuilder copilotDeprecatedSec = new StringBuilder("\n## Deprecated Elements\nDo not extend these elements. Migrate callers to the listed replacement:\n\n");
        StringBuilder qwenDeprecatedSec   = new StringBuilder("\n## ⚠️ DEPRECATED ELEMENTS\nDo not extend or build on these elements — migrate callers to the replacement.\n\n");
        StringBuilder geminiDeprecatedSec = new StringBuilder();
        for (Element e : collector.deprecated())
            appendDeprecated(e, cursorDeprecatedSec, claudeDeprecatedSec, codexDeprecatedSec, copilotDeprecatedSec, qwenDeprecatedSec, geminiDeprecatedSec);

        StringBuilder cursorObservabilitySec = new StringBuilder("\n## 📡 OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the affected dashboard.\n\n");
        StringBuilder claudeObservabilitySec = new StringBuilder("  <observability_instrumentation>\n");
        StringBuilder codexObservabilitySec  = new StringBuilder("\n## 📡 OBSERVABILITY INSTRUMENTATION\nThese elements carry instrumentation watched by dashboards/alerts. Do not remove or rename without flagging.\n\n");
        StringBuilder copilotObservabilitySec = new StringBuilder("\n## Observability Instrumentation\nThe following elements have metrics/traces/logs watched by dashboards. Do not delete or rename them silently:\n\n");
        StringBuilder qwenObservabilitySec   = new StringBuilder("\n## 📡 OBSERVABILITY INSTRUMENTATION\nThese elements carry instrumentation watched by dashboards. Preserve every metric, trace, and log statement.\n\n");
        StringBuilder geminiObservabilitySec = new StringBuilder();
        for (Element e : collector.observability())
            appendObservability(e, cursorObservabilitySec, claudeObservabilitySec, codexObservabilitySec, copilotObservabilitySec, qwenObservabilitySec, geminiObservabilitySec);

        StringBuilder cursorRegulationSec = new StringBuilder("\n## 📜 REGULATORY COMPLIANCE\nThe following elements implement specific compliance clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.\n\n");
        StringBuilder claudeRegulationSec = new StringBuilder("  <regulatory_elements>\n");
        StringBuilder codexRegulationSec  = new StringBuilder("\n## 📜 REGULATORY COMPLIANCE\nThese elements implement specific compliance clauses. Document compliance impact for every change.\n\n");
        StringBuilder copilotRegulationSec = new StringBuilder("\n## Regulatory Compliance\nThese elements implement compliance clauses. Document the compliance impact of every change:\n\n");
        StringBuilder qwenRegulationSec   = new StringBuilder("\n## 📜 REGULATORY COMPLIANCE\nThese elements implement compliance clauses — document compliance impact and never weaken the requirement.\n\n");
        StringBuilder geminiRegulationSec = new StringBuilder();
        for (Element e : collector.regulation())
            appendRegulation(e, cursorRegulationSec, claudeRegulationSec, codexRegulationSec, copilotRegulationSec, qwenRegulationSec, geminiRegulationSec);

        for (Element e : collector.parallelTests())
            appendParallelTests(e, cursorParallelTestsSec, claudeParallelTestsSec, codexParallelTestsSec, copilotParallelTestsSec, qwenParallelTestsSec, geminiParallelTestsSec, windsurfParallelTestsSec, zedParallelTestsSec);
        for (Element e : collector.legacyBridge())
            appendLegacyBridge(e, cursorLegacyBridgeSec, claudeLegacyBridgeSec, codexLegacyBridgeSec, copilotLegacyBridgeSec, qwenLegacyBridgeSec, geminiLegacyBridgeSec, windsurfLegacyBridgeSec, zedLegacyBridgeSec);
        for (Element e : collector.architecture())
            appendArchitecture(e, cursorArchitectureSec, claudeArchitectureSec, codexArchitectureSec, copilotArchitectureSec, qwenArchitectureSec, geminiArchitectureSec, windsurfArchitectureSec, zedArchitectureSec);
        for (Element e : collector.publicApi())
            appendPublicAPI(e, cursorPublicApiSec, claudePublicApiSec, codexPublicApiSec, copilotPublicApiSec, qwenPublicApiSec, geminiPublicApiSec, windsurfPublicApiSec, zedPublicApiSec);
        for (Element e : collector.strictExceptions())
            appendStrictExceptions(e, cursorStrictExceptionsSec, claudeStrictExceptionsSec, codexStrictExceptionsSec, copilotStrictExceptionsSec, qwenStrictExceptionsSec, geminiStrictExceptionsSec, windsurfStrictExceptionsSec, zedStrictExceptionsSec);
        for (Element e : collector.strictTypes())
            appendStrictTypes(e, cursorStrictTypesSec, claudeStrictTypesSec, codexStrictTypesSec, copilotStrictTypesSec, qwenStrictTypesSec, geminiStrictTypesSec, windsurfStrictTypesSec, zedStrictTypesSec);
        for (Element e : collector.internationalized())
            appendInternationalized(e, cursorInternationalizedSec, claudeInternationalizedSec, codexInternationalizedSec, copilotInternationalizedSec, qwenInternationalizedSec, geminiInternationalizedSec, windsurfInternationalizedSec, zedInternationalizedSec);
        for (Element e : collector.strictClasspath())
            appendStrictClasspath(e, cursorStrictClasspathSec, claudeStrictClasspathSec, codexStrictClasspathSec, copilotStrictClasspathSec, qwenStrictClasspathSec, geminiStrictClasspathSec, windsurfStrictClasspathSec, zedStrictClasspathSec);
        for (Element e : collector.schemaSafe())
            appendSchemaSafe(e, cursorSchemaSafeSec, claudeSchemaSafeSec, codexSchemaSafeSec, copilotSchemaSafeSec, qwenSchemaSafeSec, geminiSchemaSafeSec, windsurfSchemaSafeSec, zedSchemaSafeSec);

        StringBuilder cursorIdempotentSec  = new StringBuilder("\n## ♻️ IDEMPOTENCY GUARANTEES\nThe following operations are idempotent. Multiple invocations MUST produce the same result as a single invocation. Never introduce side effects that break this guarantee.\n\n");
        StringBuilder claudeIdempotentSec  = new StringBuilder("  <idempotent_elements>\n");
        StringBuilder codexIdempotentSec   = new StringBuilder("\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent (multiple invocations = same result as once):\n\n");
        StringBuilder copilotIdempotentSec = new StringBuilder("\n## Idempotency Guarantees\nThe following operations are idempotent — calling them multiple times is safe:\n\n");
        StringBuilder qwenIdempotentSec    = new StringBuilder("\n## ♻️ IDEMPOTENCY GUARANTEES\nMultiple invocations of these operations must produce the same result:\n\n");
        StringBuilder geminiIdempotentSec  = new StringBuilder();
        StringBuilder windsurfIdempotentSec = new StringBuilder("\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — multiple calls = same as one call:\n\n");
        StringBuilder zedIdempotentSec      = new StringBuilder("\n## Idempotency\nThese operations must remain idempotent:\n\n");
        for (Element e : collector.idempotent())
            appendIdempotent(e, cursorIdempotentSec, claudeIdempotentSec, codexIdempotentSec, copilotIdempotentSec, qwenIdempotentSec, geminiIdempotentSec, windsurfIdempotentSec, zedIdempotentSec);

        StringBuilder cursorFeatureFlagSec  = new StringBuilder("\n## 🚩 FEATURE FLAG GATED CODE\nThe following elements are gated behind a feature flag. Do not assume the flag is always active. Preserve the flag check.\n\n");
        StringBuilder claudeFeatureFlagSec  = new StringBuilder("  <feature_flag_elements>\n");
        StringBuilder codexFeatureFlagSec   = new StringBuilder("\n## 🚩 FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume the flag is always active:\n\n");
        StringBuilder copilotFeatureFlagSec = new StringBuilder("\n## Feature Flag Gated Code\nThe following elements are gated behind a feature flag — always preserve the flag check:\n\n");
        StringBuilder qwenFeatureFlagSec    = new StringBuilder("\n## 🚩 FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume it is always active:\n\n");
        StringBuilder geminiFeatureFlagSec  = new StringBuilder();
        StringBuilder windsurfFeatureFlagSec = new StringBuilder("\n## 🚩 FEATURE FLAG GATED CODE\nNever assume these feature flags are always active — preserve all flag checks:\n\n");
        StringBuilder zedFeatureFlagSec      = new StringBuilder("\n## Feature Flags\nThese elements are behind feature flags — never assume always active:\n\n");
        for (Element e : collector.featureFlag())
            appendFeatureFlag(e, cursorFeatureFlagSec, claudeFeatureFlagSec, codexFeatureFlagSec, copilotFeatureFlagSec, qwenFeatureFlagSec, geminiFeatureFlagSec, windsurfFeatureFlagSec, zedFeatureFlagSec);

        StringBuilder cursorSecureSec  = new StringBuilder("\n## 🔐 SECURITY-CRITICAL CODE\nThe following elements are security-critical. AI must not weaken security properties. Any change must be reviewed for security impact.\n\n");
        StringBuilder claudeSecureSec  = new StringBuilder("  <security_elements>\n");
        StringBuilder codexSecureSec   = new StringBuilder("\n## 🔐 SECURITY-CRITICAL CODE\nDo not weaken security properties of these elements. Review every change for security impact:\n\n");
        StringBuilder copilotSecureSec = new StringBuilder("\n## Security-Critical Code\nThe following elements are security-critical — do not weaken their security properties:\n\n");
        StringBuilder qwenSecureSec    = new StringBuilder("\n## 🔐 SECURITY-CRITICAL CODE\nNever weaken security properties of these elements. Flag any change for security review:\n\n");
        StringBuilder geminiSecureSec  = new StringBuilder();
        StringBuilder windsurfSecureSec = new StringBuilder("\n## 🔐 SECURITY-CRITICAL CODE\nNever weaken security properties of these elements. Every change requires security review:\n\n");
        StringBuilder zedSecureSec      = new StringBuilder("\n## Security-Critical Code\nNever weaken security properties of these elements:\n\n");
        for (Element e : collector.secure())
            appendSecure(e, cursorSecureSec, claudeSecureSec, codexSecureSec, copilotSecureSec, qwenSecureSec, geminiSecureSec, windsurfSecureSec, zedSecureSec);

        // Gemini composition (locked + context + audit go before the rest)
        if (!collector.locked().isEmpty()) {
            geminiMd.append("\n## LOCKED FILES (DO NOT MODIFY)\nDo not suggest modifications to the following files:\n\n").append(geminiLocked);
        }
        if (!collector.context().isEmpty()) {
            geminiMd.append("\n## CONTEXTUAL RULES\nApply the following context when assisting with these files:\n\n").append(geminiContext);
        }
        if (!collector.audit().isEmpty()) {
            geminiMd.append("\n## CONTINUOUS AUDIT REQUIREMENTS\nYou are acting as a Senior Staff Engineer. Whenever you write code for the files listed below, you must ensure your completions and chat responses strictly prevent the listed vulnerabilities:\n\n").append(geminiAuditSec);
        }

        // Append the per-section blocks to their primary platform builders, in original order
        if (!collector.audit().isEmpty()) {
            cursorRules.append(cursorAuditSec);
            claudeAuditSec.append("  </audit_requirements>\n");
            claudeMd.append(claudeAuditSec);
            claudeMd.append("\n<rule>\n  If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed <vulnerability_check> items. If your code introduces these vulnerabilities, you must rewrite it before displaying it to the user.\n</rule>\n");
            codexAgents.append(codexAuditSec);
            copilot.append(copilotAuditSec);
            qwenMd.append(qwenAuditSec);
            if (windsurfActive) windsurfRules.append(windsurfAuditSec);
            if (zedActive)      zedRules.append(zedAuditSec);
        }
        if (!collector.ignore().isEmpty()) {
            cursorRules.append(cursorIgnoreSection);
            claudeIgnoreSec.append("  </ignored_elements>\n");
            claudeMd.append(claudeIgnoreSec);
            claudeMd.append("\n<rule>Never reference or suggest changes to any element listed in <ignored_elements>. Treat these as if they do not exist.</rule>\n");
            codexAgents.append(codexIgnoreSec);
            geminiMd.append(geminiIgnoreSec);
            copilot.append(copilotIgnoreSec);
            qwenMd.append(qwenIgnoreSec);
            if (windsurfActive) windsurfRules.append(windsurfIgnoreSection);
            if (zedActive)      zedRules.append(zedIgnoreSection);
        }
        if (!collector.draft().isEmpty()) {
            cursorRules.append(cursorDraftSec);
            claudeDraftSec.append("  </implementation_tasks>\n");
            claudeMd.append(claudeDraftSec);
            codexAgents.append(codexDraftSec);
            geminiMd.append(geminiDraftSec);
            copilot.append(copilotDraftSec);
            qwenMd.append(qwenDraftSec);
            if (windsurfActive) windsurfRules.append(windsurfDraftSection);
            if (zedActive)      zedRules.append(zedDraftSection);
        }
        if (!collector.privacy().isEmpty()) {
            cursorRules.append(cursorPrivacySec);
            claudePrivacySec.append("  </pii_guardrails>\n");
            claudeMd.append(claudePrivacySec);
            claudeMd.append("\n<rule>\n  Never include runtime values of elements listed in <pii_guardrails> in logs, console output, external API calls, test fixtures, mock data, or code suggestions. Treat their values as strictly confidential.\n</rule>\n");
            codexAgents.append(codexPrivacySec);
            geminiMd.append(geminiPrivacySec);
            copilot.append(copilotPrivacySec);
            qwenMd.append(qwenPrivacySec);
            if (windsurfActive) windsurfRules.append(windsurfPrivacySection);
            if (zedActive)      zedRules.append(zedPrivacySection);
        }
        if (!collector.core().isEmpty()) {
            cursorRules.append(cursorCoreSec);
            claudeCoreSec.append("  </core_elements>\n");
            claudeMd.append(claudeCoreSec);
            claudeMd.append("\n<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>\n");
            codexAgents.append(codexCoreSec);
            copilot.append(copilotCoreSec);
            qwenMd.append(qwenCoreSec);
            geminiMd.append("\n## CORE FUNCTIONALITY (EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution:\n\n").append(geminiCoreSec);
            if (windsurfActive) windsurfRules.append(windsurfCoreSection);
            if (zedActive)      zedRules.append(zedCoreSection);
        }
        if (!collector.performance().isEmpty()) {
            cursorRules.append(cursorPerfSec);
            claudePerfSec.append("  </performance_constraints>\n");
            claudeMd.append(claudePerfSec);
            claudeMd.append("\n<rule>Elements listed in <performance_constraints> are on a hot path. Never introduce O(n²) or worse complexity. Always reason about time and space complexity before suggesting changes.</rule>\n");
            codexAgents.append(codexPerfSec);
            copilot.append(copilotPerfSec);
            qwenMd.append(qwenPerfSec);
            geminiMd.append("\n## PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) complexity into these elements. Always reason about complexity before proposing changes:\n\n").append(geminiPerfSec);
            if (windsurfActive) windsurfRules.append(windsurfPerfSection);
            if (zedActive)      zedRules.append(zedPerfSection);
        }
        if (!collector.contract().isEmpty()) {
            cursorRules.append(cursorContractSec);
            claudeContractSec.append("  </contract_signatures>\n");
            claudeMd.append(claudeContractSec);
            claudeMd.append("\n<rule>You may refactor the internal logic of elements listed in <contract_signatures>, but you MUST NOT change their public signatures: method names, parameter types, parameter order, return types, or checked exceptions.</rule>\n");
            codexAgents.append(codexContractSec);
            copilot.append(copilotContractSec);
            qwenMd.append(qwenContractSec);
            geminiMd.append("\n## CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions:\n\n").append(geminiContractSec);
            if (windsurfActive) windsurfRules.append(windsurfContractSection);
            if (zedActive)      zedRules.append(zedContractSection);
        }
        if (!collector.testDriven().isEmpty()) {
            cursorRules.append(cursorTestDrivenSec);
            claudeTestDrivenSec.append("  </test_driven_requirements>\n");
            claudeMd.append(claudeTestDrivenSec);
            claudeMd.append("\n<rule>For any element listed in <test_driven_requirements>, you MUST provide both the implementation change AND the corresponding test code update in a single response. Changes without tests are incomplete and must not be proposed.</rule>\n");
            codexAgents.append(codexTestDrivenSec);
            copilot.append(copilotTestDrivenSec);
            qwenMd.append(qwenTestDrivenSec);
            geminiMd.append("\n## TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by matching test code in the same response:\n\n").append(geminiTestDrivenSec);
            if (windsurfActive) windsurfRules.append(windsurfTestDrivenSection);
            if (zedActive)      zedRules.append(zedTestDrivenSection);
        }
        if (!collector.threadSafe().isEmpty()) {
            cursorRules.append(cursorThreadSafeSec);
            claudeThreadSafeSec.append("  </thread_safe_elements>\n");
            claudeMd.append(claudeThreadSafeSec);
            claudeMd.append("\n<rule>Elements listed in <thread_safe_elements> are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning in the change description.</rule>\n");
            codexAgents.append(codexThreadSafeSec);
            copilot.append(copilotThreadSafeSec);
            qwenMd.append(qwenThreadSafeSec);
            geminiMd.append("\n## THREAD-SAFE BY DESIGN\nThese elements are thread-safe by design — preserve the synchronization invariant on every change:\n\n").append(geminiThreadSafeSec);
        }
        if (!collector.immutable().isEmpty()) {
            cursorRules.append(cursorImmutableSec);
            claudeImmutableSec.append("  </immutable_types>\n");
            claudeMd.append(claudeImmutableSec);
            claudeMd.append("\n<rule>Types listed in <immutable_types> are immutable by design. Never introduce non-final fields, setters, or methods that mutate instance state.</rule>\n");
            codexAgents.append(codexImmutableSec);
            copilot.append(copilotImmutableSec);
            qwenMd.append(qwenImmutableSec);
            geminiMd.append("\n## IMMUTABLE TYPES\nThe following types are immutable. Do not introduce non-final fields, setters, or mutating methods:\n\n").append(geminiImmutableSec);
        }
        if (!collector.deprecated().isEmpty()) {
            cursorRules.append(cursorDeprecatedSec);
            claudeDeprecatedSec.append("  </deprecated_elements>\n");
            claudeMd.append(claudeDeprecatedSec);
            claudeMd.append("\n<rule>Elements listed in <deprecated_elements> are scheduled for removal. Do not extend them. When working with code that calls them, suggest migrating to the listed replacement.</rule>\n");
            codexAgents.append(codexDeprecatedSec);
            copilot.append(copilotDeprecatedSec);
            qwenMd.append(qwenDeprecatedSec);
            geminiMd.append("\n## DEPRECATED ELEMENTS\nThe following elements are deprecated. Suggest migration to the named replacement for any caller:\n\n").append(geminiDeprecatedSec);
        }
        if (!collector.observability().isEmpty()) {
            cursorRules.append(cursorObservabilitySec);
            claudeObservabilitySec.append("  </observability_instrumentation>\n");
            claudeMd.append(claudeObservabilitySec);
            claudeMd.append("\n<rule>Elements listed in <observability_instrumentation> publish metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the corresponding dashboard update.</rule>\n");
            codexAgents.append(codexObservabilitySec);
            copilot.append(copilotObservabilitySec);
            qwenMd.append(qwenObservabilitySec);
            geminiMd.append("\n## OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements watched by dashboards. Preserve every instrumentation point:\n\n").append(geminiObservabilitySec);
        }
        if (!collector.regulation().isEmpty()) {
            cursorRules.append(cursorRegulationSec);
            claudeRegulationSec.append("  </regulatory_elements>\n");
            claudeMd.append(claudeRegulationSec);
            claudeMd.append("\n<rule>Elements listed in <regulatory_elements> implement specific regulatory clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.</rule>\n");
            codexAgents.append(codexRegulationSec);
            copilot.append(copilotRegulationSec);
            qwenMd.append(qwenRegulationSec);
            geminiMd.append("\n## REGULATORY COMPLIANCE\nThe following elements implement compliance clauses. Document compliance impact for every change:\n\n").append(geminiRegulationSec);
        }

        if (!collector.parallelTests().isEmpty()) {
            cursorRules.append(cursorParallelTestsSec);
            claudeParallelTestsSec.append("  </test_isolation_elements>\n");
            claudeMd.append(claudeParallelTestsSec);
            claudeMd.append("\n<rule>For elements in <test_isolation_elements>, all generated or modified tests MUST run in complete isolation (no shared state, external resource conflicts, or order dependencies).</rule>\n");
            codexAgents.append(codexParallelTestsSec);
            copilot.append(copilotParallelTestsSec);
            qwenMd.append(qwenParallelTestsSec);
            geminiMd.append("\n## STRICT TEST ISOLATION\nTests for these elements must run in complete isolation without sharing mutable state:\n\n").append(geminiParallelTestsSec);
            if (windsurfActive) windsurfRules.append(windsurfParallelTestsSec);
            if (zedActive)      zedRules.append(zedParallelTestsSec);
        }
        if (!collector.legacyBridge().isEmpty()) {
            cursorRules.append(cursorLegacyBridgeSec);
            claudeLegacyBridgeSec.append("  </legacy_bridge_elements>\n");
            claudeMd.append(claudeLegacyBridgeSec);
            claudeMd.append("\n<rule>Do not modernise, elegant-ize, or refactor structural patterns of elements in <legacy_bridge_elements>. Only modify internal business logic as explicitly requested.</rule>\n");
            codexAgents.append(codexLegacyBridgeSec);
            copilot.append(copilotLegacyBridgeSec);
            qwenMd.append(qwenLegacyBridgeSec);
            geminiMd.append("\n## LEGACY COMPATIBILITY BRIDGE\nDo not restructure compatibility bridges. Only modify business logic:\n\n").append(geminiLegacyBridgeSec);
            if (windsurfActive) windsurfRules.append(windsurfLegacyBridgeSec);
            if (zedActive)      zedRules.append(zedLegacyBridgeSec);
        }
        if (!collector.architecture().isEmpty()) {
            cursorRules.append(cursorArchitectureSec);
            claudeArchitectureSec.append("  </architecture_elements>\n");
            claudeMd.append(claudeArchitectureSec);
            claudeMd.append("\n<rule>Respect layered architectural constraints in <architecture_elements>. Boundary crossing references are strictly prohibited.</rule>\n");
            codexAgents.append(codexArchitectureSec);
            copilot.append(copilotArchitectureSec);
            qwenMd.append(qwenArchitectureSec);
            geminiMd.append("\n## ARCHITECTURAL BOUNDARY CONSTRAINTS\nRespect architectural layering. Boundary crossing references are prohibited:\n\n").append(geminiArchitectureSec);
            if (windsurfActive) windsurfRules.append(windsurfArchitectureSec);
            if (zedActive)      zedRules.append(zedArchitectureSec);
        }
        if (!collector.publicApi().isEmpty()) {
            cursorRules.append(cursorPublicApiSec);
            claudePublicApiSec.append("  </public_api_elements>\n");
            claudeMd.append(claudePublicApiSec);
            claudeMd.append("\n<rule>Elements in <public_api_elements> expose public API. Preserve public signature, Javadoc, and backwards compatibility without exceptions.</rule>\n");
            codexAgents.append(codexPublicApiSec);
            copilot.append(copilotPublicApiSec);
            qwenMd.append(qwenPublicApiSec);
            geminiMd.append("\n## PUBLIC API SURFACE PROTECTION\nPreserve public signatures, Javadoc, and backwards compatibility:\n\n").append(geminiPublicApiSec);
            if (windsurfActive) windsurfRules.append(windsurfPublicApiSec);
            if (zedActive)      zedRules.append(zedPublicApiSec);
        }
        if (!collector.strictExceptions().isEmpty()) {
            cursorRules.append(cursorStrictExceptionsSec);
            claudeStrictExceptionsSec.append("  </strict_exceptions_elements>\n");
            claudeMd.append(claudeStrictExceptionsSec);
            claudeMd.append("\n<rule>Catching or throwing generic Exception/Throwable is strictly prohibited in <strict_exceptions_elements>. Precise or custom exceptions required.</rule>\n");
            codexAgents.append(codexStrictExceptionsSec);
            copilot.append(copilotStrictExceptionsSec);
            qwenMd.append(qwenStrictExceptionsSec);
            geminiMd.append("\n## STRICT EXCEPTION HANDLING\nCatching/throwing generic Exception is prohibited. Use precise exceptions:\n\n").append(geminiStrictExceptionsSec);
            if (windsurfActive) windsurfRules.append(windsurfStrictExceptionsSec);
            if (zedActive)      zedRules.append(zedStrictExceptionsSec);
        }
        if (!collector.strictTypes().isEmpty()) {
            cursorRules.append(cursorStrictTypesSec);
            claudeStrictTypesSec.append("  </strict_types_elements>\n");
            claudeMd.append(claudeStrictTypesSec);
            claudeMd.append("\n<rule>Loose typing (Object, Map<String, Object>, raw types) is strictly prohibited in <strict_types_elements>. Enforce type safety.</rule>\n");
            codexAgents.append(codexStrictTypesSec);
            copilot.append(copilotStrictTypesSec);
            qwenMd.append(qwenStrictTypesSec);
            geminiMd.append("\n## STRICT TYPE SAFETY\nLoose typing is prohibited. Strongly-typed models required:\n\n").append(geminiStrictTypesSec);
            if (windsurfActive) windsurfRules.append(windsurfStrictTypesSec);
            if (zedActive)      zedRules.append(zedStrictTypesSec);
        }
        if (!collector.internationalized().isEmpty()) {
            cursorRules.append(cursorInternationalizedSec);
            claudeInternationalizedSec.append("  </internationalized_elements>\n");
            claudeMd.append(claudeInternationalizedSec);
            claudeMd.append("\n<rule>Do not hardcode user-facing strings in <internationalized_elements>. Resolve all text via localization resource/message bundles.</rule>\n");
            codexAgents.append(codexInternationalizedSec);
            copilot.append(copilotInternationalizedSec);
            qwenMd.append(qwenInternationalizedSec);
            geminiMd.append("\n## INTERNATIONALIZATION MANDATE\nUser-facing strings must not be hardcoded; retrieve from resources:\n\n").append(geminiInternationalizedSec);
            if (windsurfActive) windsurfRules.append(windsurfInternationalizedSec);
            if (zedActive)      zedRules.append(zedInternationalizedSec);
        }
        if (!collector.strictClasspath().isEmpty()) {
            cursorRules.append(cursorStrictClasspathSec);
            claudeStrictClasspathSec.append("  </strict_classpath_elements>\n");
            claudeMd.append(claudeStrictClasspathSec);
            claudeMd.append("\n<rule>Dynamic class loading, custom classloaders, reflection hacks, or unverified external code are prohibited in <strict_classpath_elements>.</rule>\n");
            codexAgents.append(codexStrictClasspathSec);
            copilot.append(copilotStrictClasspathSec);
            qwenMd.append(qwenStrictClasspathSec);
            geminiMd.append("\n## STRICT CLASSPATH INTEGRITY\nDynamic class loading and reflection hacks are strictly prohibited:\n\n").append(geminiStrictClasspathSec);
            if (windsurfActive) windsurfRules.append(windsurfStrictClasspathSec);
            if (zedActive)      zedRules.append(zedStrictClasspathSec);
        }
        if (!collector.schemaSafe().isEmpty()) {
            cursorRules.append(cursorSchemaSafeSec);
            claudeSchemaSafeSec.append("  </schema_safe_elements>\n");
            claudeMd.append(claudeSchemaSafeSec);
            claudeMd.append("\n<rule>Database or contract schema / serialization safety must be preserved in <schema_safe_elements>. Do not alter structures without migration paths.</rule>\n");
            codexAgents.append(codexSchemaSafeSec);
            copilot.append(copilotSchemaSafeSec);
            qwenMd.append(qwenSchemaSafeSec);
            geminiMd.append("\n## SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n").append(geminiSchemaSafeSec);
            if (windsurfActive) windsurfRules.append(windsurfSchemaSafeSec);
            if (zedActive)      zedRules.append(zedSchemaSafeSec);
        }
        if (!collector.idempotent().isEmpty()) {
            cursorRules.append(cursorIdempotentSec);
            claudeIdempotentSec.append("  </idempotent_elements>\n");
            claudeMd.append(claudeIdempotentSec);
            claudeMd.append("\n<rule>Operations listed in <idempotent_elements> must remain idempotent. Never introduce side effects that cause repeated invocations to produce different results.</rule>\n");
            codexAgents.append(codexIdempotentSec);
            copilot.append(copilotIdempotentSec);
            qwenMd.append(qwenIdempotentSec);
            geminiMd.append("\n## IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — calling them multiple times must produce the same result:\n\n").append(geminiIdempotentSec);
            if (windsurfActive) windsurfRules.append(windsurfIdempotentSec);
            if (zedActive)      zedRules.append(zedIdempotentSec);
        }
        if (!collector.featureFlag().isEmpty()) {
            cursorRules.append(cursorFeatureFlagSec);
            claudeFeatureFlagSec.append("  </feature_flag_elements>\n");
            claudeMd.append(claudeFeatureFlagSec);
            claudeMd.append("\n<rule>Elements listed in <feature_flag_elements> are gated by a feature flag. Always preserve the flag check — never assume the flag is always active.</rule>\n");
            codexAgents.append(codexFeatureFlagSec);
            copilot.append(copilotFeatureFlagSec);
            qwenMd.append(qwenFeatureFlagSec);
            geminiMd.append("\n## FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume the flag is always active:\n\n").append(geminiFeatureFlagSec);
            if (windsurfActive) windsurfRules.append(windsurfFeatureFlagSec);
            if (zedActive)      zedRules.append(zedFeatureFlagSec);
        }
        if (!collector.secure().isEmpty()) {
            cursorRules.append(cursorSecureSec);
            claudeSecureSec.append("  </security_elements>\n");
            claudeMd.append(claudeSecureSec);
            claudeMd.append("\n<rule>Elements listed in <security_elements> are security-critical. Never weaken their security properties. Every proposed change must be explicitly reviewed for security impact.</rule>\n");
            codexAgents.append(codexSecureSec);
            copilot.append(copilotSecureSec);
            qwenMd.append(qwenSecureSec);
            geminiMd.append("\n## SECURITY-CRITICAL CODE\nDo not weaken security properties of these elements. Flag any change for security review:\n\n").append(geminiSecureSec);
            if (windsurfActive) windsurfRules.append(windsurfSecureSec);
            if (zedActive)      zedRules.append(zedSecureSec);
        }

        claudeMd.append("</project_guardrails>\n");
        claudeMd.append("\n<rule>Never propose edits to files listed in <locked_files>.</rule>\n");

        // llms.txt + llms-full.txt assembly — only if at least one of them is opted in.
        if (llmsActive) {
            llmsTxt.append(llmsTxtContext);
            if (!collector.audit().isEmpty())       llmsTxt.append(llmsTxtAudit);
            if (!collector.ignore().isEmpty())      llmsTxt.append(llmsTxtIgnore);
            if (!collector.draft().isEmpty())       llmsTxt.append(llmsTxtDraft);
            if (!collector.privacy().isEmpty())     llmsTxt.append(llmsTxtPrivacy);
            if (!collector.core().isEmpty())        llmsTxt.append(llmsTxtCore);
            if (!collector.performance().isEmpty()) llmsTxt.append(llmsTxtPerformance);
            if (!collector.contract().isEmpty())    llmsTxt.append(llmsTxtContract);
            if (!collector.testDriven().isEmpty())  llmsTxt.append(llmsTxtTestDriven);
            if (!collector.threadSafe().isEmpty())    llmsTxt.append(llmsTxtThreadSafe);
            if (!collector.immutable().isEmpty())     llmsTxt.append(llmsTxtImmutable);
            if (!collector.deprecated().isEmpty())    llmsTxt.append(llmsTxtDeprecated);
            if (!collector.observability().isEmpty()) llmsTxt.append(llmsTxtObservability);
            if (!collector.regulation().isEmpty())    llmsTxt.append(llmsTxtRegulation);
            if (!collector.parallelTests().isEmpty())     llmsTxt.append(llmsTxtParallelTests);
            if (!collector.legacyBridge().isEmpty())      llmsTxt.append(llmsTxtLegacyBridge);
            if (!collector.architecture().isEmpty())      llmsTxt.append(llmsTxtArchitecture);
            if (!collector.publicApi().isEmpty())         llmsTxt.append(llmsTxtPublicApi);
            if (!collector.strictExceptions().isEmpty())  llmsTxt.append(llmsTxtStrictExceptions);
            if (!collector.strictTypes().isEmpty())       llmsTxt.append(llmsTxtStrictTypes);
            if (!collector.internationalized().isEmpty())  llmsTxt.append(llmsTxtInternationalized);
            if (!collector.strictClasspath().isEmpty())   llmsTxt.append(llmsTxtStrictClasspath);
            if (!collector.schemaSafe().isEmpty())        llmsTxt.append(llmsTxtSchemaSafe);
            if (!collector.idempotent().isEmpty())        llmsTxt.append(llmsTxtIdempotent);
            if (!collector.featureFlag().isEmpty())       llmsTxt.append(llmsTxtFeatureFlag);
            if (!collector.secure().isEmpty())            llmsTxt.append(llmsTxtSecure);
        }
        if (llmsFullActive) {
            llmsFullTxt.append(llmsFullTxtContext);
            if (!collector.audit().isEmpty())       llmsFullTxt.append(llmsFullTxtAudit);
            if (!collector.ignore().isEmpty())      llmsFullTxt.append(llmsFullTxtIgnore);
            if (!collector.draft().isEmpty())       llmsFullTxt.append(llmsFullTxtDraft);
            if (!collector.privacy().isEmpty())     llmsFullTxt.append(llmsFullTxtPrivacy);
            if (!collector.core().isEmpty())        llmsFullTxt.append(llmsFullTxtCore);
            if (!collector.performance().isEmpty()) llmsFullTxt.append(llmsFullTxtPerformance);
            if (!collector.contract().isEmpty())    llmsFullTxt.append(llmsFullTxtContract);
            if (!collector.testDriven().isEmpty())  llmsFullTxt.append(llmsFullTxtTestDriven);
            if (!collector.threadSafe().isEmpty())    llmsFullTxt.append(llmsFullTxtThreadSafe);
            if (!collector.immutable().isEmpty())     llmsFullTxt.append(llmsFullTxtImmutable);
            if (!collector.deprecated().isEmpty())    llmsFullTxt.append(llmsFullTxtDeprecated);
            if (!collector.observability().isEmpty()) llmsFullTxt.append(llmsFullTxtObservability);
            if (!collector.regulation().isEmpty())    llmsFullTxt.append(llmsFullTxtRegulation);
            if (!collector.parallelTests().isEmpty())     llmsFullTxt.append(llmsFullTxtParallelTests);
            if (!collector.legacyBridge().isEmpty())      llmsFullTxt.append(llmsFullTxtLegacyBridge);
            if (!collector.architecture().isEmpty())      llmsFullTxt.append(llmsFullTxtArchitecture);
            if (!collector.publicApi().isEmpty())         llmsFullTxt.append(llmsFullTxtPublicApi);
            if (!collector.strictExceptions().isEmpty())  llmsFullTxt.append(llmsFullTxtStrictExceptions);
            if (!collector.strictTypes().isEmpty())       llmsFullTxt.append(llmsFullTxtStrictTypes);
            if (!collector.internationalized().isEmpty())  llmsFullTxt.append(llmsFullTxtInternationalized);
            if (!collector.strictClasspath().isEmpty())   llmsFullTxt.append(llmsFullTxtStrictClasspath);
            if (!collector.schemaSafe().isEmpty())        llmsFullTxt.append(llmsFullTxtSchemaSafe);
            if (!collector.idempotent().isEmpty())        llmsFullTxt.append(llmsFullTxtIdempotent);
            if (!collector.featureFlag().isEmpty())       llmsFullTxt.append(llmsFullTxtFeatureFlag);
            if (!collector.secure().isEmpty())            llmsFullTxt.append(llmsFullTxtSecure);
        }

        // v0.9.7: Cline and Junie derive their content from cursorRules (same Markdown format).
        // Cline uses identical content; Junie replaces the cursor header with its own heading.
        String clineContent = clineActive ? cursorRules.toString() : null;
        String junieContent = null;
        if (junieActive) {
            String cursorContent = cursorRules.toString();
            String cursorHeader = "# AUTO-GENERATED AI RULES\n" + generatedHeader + "# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n";
            String junieHeader = "# JetBrains Junie Guidelines\n" + generatedHeader + "# AUTO-GENERATED BY VIBETAGS. Do not edit manually.\n\n## Locked Files (Do Not Modify)\nThe following files must not be modified:\n\n";
            junieContent = cursorContent.replace(cursorHeader, junieHeader);
        }

        return new Result(buildContentMap(geminiMd, clineContent, junieContent), elementRules);
    }

    // -------------------------------------------------------------------------------------------
    // Per-annotation appenders
    // -------------------------------------------------------------------------------------------

    private void appendLocked(Element e, StringBuilder geminiLocked) {
        AILocked locked = e.getAnnotation(AILocked.class);
        String className = ElementNaming.elementPath(e);
        String reason = locked.reason();

        if (cursorActive)  cursorRules.append("* `").append(className).append("` - Reason: ").append(reason).append("\n");
        if (claudeActive)  claudeMd.append("    <file path=\"").append(className).append("\">\n      <reason>").append(reason).append("</reason>\n    </file>\n");
        if (aiexcludeActive) aiExclude.append("**/").append(e.getSimpleName()).append(".java\n");
        if (codexActive)   codexAgents.append("- **").append(className).append("**: ").append(reason).append("\n");
        if (copilotActive) copilot.append("- `").append(className).append("` - ").append(reason).append("\n");
        if (qwenActive)    qwenMd.append("* `").append(className).append("` - ").append(reason).append("\n");
        if (anyGeminiActive) geminiLocked.append("- `").append(className).append("`: ").append(reason).append("\n");

        if (llmsActive)     llmsTxt.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(reason).append("\n");
        if (llmsFullActive) llmsFullTxt.append("### ").append(className).append("\n- **Reason**: ").append(reason).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### LOCKED: ").append(className).append("\n- **Status**: Locked (Do Not Edit)\n- **Reason**: ").append(reason).append("\n\n");
        if (windsurfActive)  windsurfRules.append("* `").append(className).append("` - Reason: ").append(reason).append("\n");
        if (zedActive)       zedRules.append("- `").append(className).append("`: ").append(reason).append("\n");
        if (mentatActive)    mentatLocked.append("    {\"path\": \"").append(className).append("\", \"reason\": \"").append(reason).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"Do not modify ").append(className).append(": ").append(reason).append("\"\n");
        if (plandexActive)   plandexLocked.append("    - path: \"").append(className).append("\"\n      reason: \"").append(reason).append("\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (locked): ").append(reason).append("\n");
        if (granularActive)  appendToGranular(e, "Locked Status", "- **Reason**: " + reason);
    }

    private void appendContextHeaders() {
        claudeMd.append("  </locked_files>\n  <contextual_instructions>\n");
        cursorRules.append("\n## CONTEXTUAL RULES\n");
        codexAgents.append("\n## CONTEXTUAL RULES\n");
        copilot.append("\n## Contextual Guidelines\n");
        qwenMd.append("\n## CONTEXTUAL RULES\n");
        if (windsurfActive) windsurfRules.append("\n## CONTEXTUAL RULES\n");
        if (zedActive)      zedRules.append("\n## Context Guidelines\n");
    }

    private void appendContext(Element e, StringBuilder geminiContext) {
        AIContext context = e.getAnnotation(AIContext.class);
        String className = ElementNaming.elementPath(e);

        if (cursorActive)  cursorRules.append("* `").append(className).append("`\n  * Focus: ").append(context.focus()).append("\n  * Avoid: ").append(context.avoids()).append("\n");
        if (claudeActive)  claudeMd.append("    <file path=\"").append(className).append("\">\n      <focus>").append(context.focus()).append("</focus>\n      <avoids>").append(context.avoids()).append("</avoids>\n    </file>\n");
        if (codexActive)   codexAgents.append("- `").append(className).append("`: Focus on ").append(context.focus()).append(". Avoid ").append(context.avoids()).append(".\n");
        if (copilotActive) copilot.append("- `").append(className).append("` \n  - Focus: ").append(context.focus()).append("\n  - Avoid: ").append(context.avoids()).append("\n");
        if (qwenActive)    qwenMd.append("* `").append(className).append("` \n  * Focus: ").append(context.focus()).append("\n  * Avoid: ").append(context.avoids()).append("\n");

        if (llmsActive)     llmsTxtContext.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): Focus - ").append(context.focus()).append(". Avoid - ").append(context.avoids()).append("\n");
        if (llmsFullActive) llmsFullTxtContext.append("### ").append(className).append("\n- **Focus**: ").append(context.focus()).append("\n- **Avoid**: ").append(context.avoids()).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### CONTEXT: ").append(className).append("\n- **Focus**: ").append(context.focus()).append("\n- **Avoid**: ").append(context.avoids()).append("\n\n");
        if (windsurfActive)  windsurfRules.append("* `").append(className).append("`\n  * Focus: ").append(context.focus()).append("\n  * Avoid: ").append(context.avoids()).append("\n");
        if (zedActive)       zedRules.append("- `").append(className).append("`: Focus - ").append(context.focus()).append(". Avoid - ").append(context.avoids()).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (context): Focus - ").append(context.focus()).append(". Avoid - ").append(context.avoids()).append("\n");
        if (granularActive)  appendToGranular(e, "Context & Focus", "- **Focus**: " + context.focus() + "\n- **Avoid**: " + context.avoids());
        if (anyGeminiActive) geminiContext.append("- `").append(className).append("`: Focus - ").append(context.focus()).append(". Avoid - ").append(context.avoids()).append("\n");
    }

    private void appendIgnore(Element e, StringBuilder claudeIgnoreSec, StringBuilder codexIgnoreSec,
                              StringBuilder geminiIgnoreSec, StringBuilder copilotIgnoreSec,
                              StringBuilder qwenIgnoreSec) {
        String className = ElementNaming.elementPath(e);
        if (cursorActive)    cursorIgnoreSection.append("* `").append(className).append("` \n");
        if (claudeActive)    claudeIgnoreSec.append("    <file path=\"").append(className).append("\"/>\n");
        if (aiexcludeActive) aiExclude.append("**/").append(e.getSimpleName()).append(".java\n");
        if (codexActive)     codexIgnoreSec.append("- `").append(className).append("` \n");
        if (anyGeminiActive) geminiIgnoreSec.append("- `").append(className).append("` \n");
        if (copilotActive)   copilotIgnoreSec.append("- `").append(className).append("` \n");

        String globPattern = "**/" + e.getSimpleName() + ".java\n";
        if (cursorIgnoreFileActive)   cursorIgnoreFile.append(globPattern);
        if (claudeIgnoreFileActive)   claudeIgnoreFile.append(globPattern);
        if (copilotIgnoreFileActive)  copilotIgnoreFile.append(globPattern);
        if (qwenIgnoreFileActive)     qwenIgnoreFile.append(globPattern);
        if (codyIgnoreActive)       codyIgnoreFile.append(globPattern);
        if (supermavenIgnoreActive) supermavenIgnoreFile.append(globPattern);
        if (doubleIgnoreActive)       doubleIgnoreFile.append(globPattern);
        if (codeiumIgnoreActive)      codeiumIgnoreFile.append(globPattern);
        if (antigravityIgnoreActive)  antigravityIgnoreFile.append(globPattern);
        if (qwenActive) qwenIgnoreSec.append("* `").append(className).append("` \n");
        if (mentatActive)     mentatIgnore.append("    {\"path\": \"").append(className).append("\"},\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (excluded): treat as non-existent\n");

        if (llmsActive)     llmsTxtIgnore.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): excluded from AI context\n");
        if (llmsFullActive) llmsFullTxtIgnore.append("### ").append(className).append("\n- Excluded from AI context entirely - treat as non-existent\n\n");

        if (aiderIgnoreActive) aiderIgnore.append(globPattern);
        if (aiderConvActive)   aiderConventions.append("#### IGNORE: ").append(className).append("\n- **Instruction**: This element is strictly excluded from AI context. Do not reference it.\n\n");
        if (windsurfActive)    windsurfIgnoreSection.append("* `").append(className).append("` \n");
        if (zedActive)         zedIgnoreSection.append("- `").append(className).append("` \n");
        if (granularActive)    appendToGranular(e, "Exclusion Rule", "This element is strictly excluded from AI context. Do not reference it.");
    }

    private void appendAudit(Element e, StringBuilder cursorAuditSec, StringBuilder claudeAuditSec,
                             StringBuilder geminiAuditSec, StringBuilder codexAuditSec,
                             StringBuilder copilotAuditSec, StringBuilder qwenAuditSec,
                             StringBuilder windsurfAuditSec, StringBuilder zedAuditSec) {
        AIAudit audit = e.getAnnotation(AIAudit.class);
        if (audit == null) return;
        String[] checkFor = audit.checkFor();
        if (checkFor.length == 0) return;
        String className = ElementNaming.elementPath(e);
        String checkForJoined = String.join(", ", checkFor);

        if (cursorActive) {
            cursorAuditSec.append("* `").append(className).append("` \n  - Required Checks: ").append(checkForJoined).append("\n");
        }
        if (claudeActive) {
            claudeAuditSec.append("    <file path=\"").append(className).append("\">\n");
            for (String v : checkFor) claudeAuditSec.append("      <vulnerability_check>").append(v).append("</vulnerability_check>\n");
            claudeAuditSec.append("    </file>\n");
        }
        if (anyGeminiActive) {
            geminiAuditSec.append("File: `").append(className).append("` \nCritical Vulnerabilities to Prevent: ");
            for (String v : checkFor) geminiAuditSec.append("\n- ").append(v);
            geminiAuditSec.append("\n\n");
        }
        if (codexActive)   codexAuditSec.append("* `").append(className).append("` \n  - Required Checks: ").append(checkForJoined).append("\n");
        if (copilotActive) copilotAuditSec.append("- `").append(className).append("` \n  - Required Checks: ").append(checkForJoined).append("\n");
        if (qwenActive)    qwenAuditSec.append("* `").append(className).append("` \n  - Required Checks: ").append(checkForJoined).append("\n");

        if (llmsActive)     llmsTxtAudit.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): check for ").append(checkForJoined).append("\n");
        if (llmsFullActive) llmsFullTxtAudit.append("### ").append(className).append("\n- **Required Checks**: ").append(checkForJoined).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### SECURITY AUDIT: ").append(className).append("\n- **Required Checks**: ").append(checkForJoined).append("\n\n");
        if (windsurfActive)  windsurfAuditSec.append("* `").append(className).append("` \n  - Required Checks: ").append(checkForJoined).append("\n");
        if (zedActive)       zedAuditSec.append("- `").append(className).append("` — check for: ").append(checkForJoined).append("\n");
        if (mentatActive)    mentatAudit.append("    {\"path\": \"").append(className).append("\", \"checks\": [").append(buildJsonStringArray(checkFor)).append("]},\n");
        if (sweepActive)     sweepRules.append("  - \"Security audit required for ").append(className).append(": ").append(checkForJoined).append("\"\n");
        if (plandexActive)   plandexAudit.append("    - path: \"").append(className).append("\"\n      checks: [").append(checkForJoined).append("]\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (audit): check for ").append(checkForJoined).append("\n");
        if (granularActive)  appendToGranular(e, "Security Audit Requirements", "When modifying this element, audit for:\n- " + String.join("\n- ", checkFor));
    }

    private void appendDraft(Element e, StringBuilder cursorDraftSec, StringBuilder claudeDraftSec,
                             StringBuilder codexDraftSec, StringBuilder geminiDraftSec,
                             StringBuilder copilotDraftSec, StringBuilder qwenDraftSec) {
        AIDraft draft = e.getAnnotation(AIDraft.class);
        if (draft == null) return;
        String className = ElementNaming.elementPath(e);
        String instructions = draft.instructions();

        if (cursorActive)  cursorDraftSec.append("* `").append(className).append("` - Task: ").append(instructions).append("\n");
        if (claudeActive)  claudeDraftSec.append("    <task path=\"").append(className).append("\">\n      <instructions>").append(instructions).append("</instructions>\n    </task>\n");
        if (codexActive)   codexDraftSec.append("- **").append(className).append("**: ").append(instructions).append("\n");
        if (anyGeminiActive) geminiDraftSec.append("- `").append(className).append("`: ").append(instructions).append("\n");
        if (copilotActive) copilotDraftSec.append("- `").append(className).append("`: ").append(instructions).append("\n");
        if (qwenActive)    qwenDraftSec.append("* `").append(className).append("` - Task: ").append(instructions).append("\n");

        if (llmsActive)     llmsTxtDraft.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(instructions).append("\n");
        if (llmsFullActive) llmsFullTxtDraft.append("### ").append(className).append("\n- **Instructions**: ").append(instructions).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### DRAFT/TODO: ").append(className).append("\n- **Instruction**: ").append(instructions).append("\n\n");
        if (windsurfActive)  windsurfDraftSection.append("* `").append(className).append("` - Task: ").append(instructions).append("\n");
        if (zedActive)       zedDraftSection.append("- `").append(className).append("`: ").append(instructions).append("\n");
        if (mentatActive)    mentatDraft.append("    {\"path\": \"").append(className).append("\", \"instructions\": \"").append(instructions).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"Implementation task for ").append(className).append(": ").append(instructions).append("\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (draft): ").append(instructions).append("\n");
        if (granularActive)  appendToGranular(e, "Implementation Tasks", "- **Instruction**: " + instructions);
    }

    private void appendPrivacy(Element e, StringBuilder cursorPrivacySec, StringBuilder claudePrivacySec,
                               StringBuilder codexPrivacySec, StringBuilder geminiPrivacySec,
                               StringBuilder copilotPrivacySec, StringBuilder qwenPrivacySec) {
        AIPrivacy privacy = e.getAnnotation(AIPrivacy.class);
        if (privacy == null) return;
        String className = ElementNaming.elementPath(e);
        String reason = privacy.reason();

        if (cursorActive)  cursorPrivacySec.append("* `").append(className).append("` - ").append(reason).append("\n");
        if (claudeActive)  claudePrivacySec.append("    <element path=\"").append(className).append("\">\n      <reason>").append(reason).append("</reason>\n    </element>\n");
        if (codexActive)   codexPrivacySec.append("- `").append(className).append("`: ").append(reason).append("\n");
        if (anyGeminiActive) geminiPrivacySec.append("- `").append(className).append("`: ").append(reason).append("\n");
        if (copilotActive) copilotPrivacySec.append("- `").append(className).append("` - ").append(reason).append("\n");
        if (qwenActive)    qwenPrivacySec.append("* `").append(className).append("` - ").append(reason).append("\n");

        if (llmsActive)     llmsTxtPrivacy.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(reason).append("\n");
        if (llmsFullActive) llmsFullTxtPrivacy.append("### ").append(className).append("\n- **Reason**: ").append(reason).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### PRIVACY/PII: ").append(className).append("\n- **Safety Rule**: Never log or expose runtime values of this element.\n- **Reason**: ").append(reason).append("\n\n");
        if (windsurfActive)  windsurfPrivacySection.append("* `").append(className).append("` - ").append(reason).append("\n");
        if (zedActive)       zedPrivacySection.append("- `").append(className).append("`: ").append(reason).append("\n");
        if (mentatActive)    mentatPrivacy.append("    {\"path\": \"").append(className).append("\", \"reason\": \"").append(reason).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"PII protection required for ").append(className).append(": never log or expose runtime values\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (privacy): ").append(reason).append("\n");
        if (granularActive)  appendToGranular(e, "PII / Privacy Guardrails", "- **Rule**: Never log or expose runtime values of this element.\n- **Reason**: " + reason);
    }

    private void appendCore(Element e, StringBuilder cursorCoreSec, StringBuilder claudeCoreSec,
                            StringBuilder codexCoreSec, StringBuilder copilotCoreSec,
                            StringBuilder qwenCoreSec, StringBuilder geminiCoreSec) {
        AICore core = e.getAnnotation(AICore.class);
        if (core == null) return;
        String className = ElementNaming.elementPath(e);
        String sensitivity = core.sensitivity();
        String note = core.note();

        if (cursorActive)  cursorCoreSec.append("* `").append(className).append("` - Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
        if (claudeActive)  claudeCoreSec.append("    <element path=\"").append(className).append("\">\n      <sensitivity>").append(sensitivity).append("</sensitivity>\n      <note>").append(note).append("</note>\n    </element>\n");
        if (codexActive)   codexCoreSec.append("- **").append(className).append("** (sensitivity: ").append(sensitivity).append("): ").append(note).append("\n");
        if (copilotActive) copilotCoreSec.append("- `").append(className).append("` — sensitivity: ").append(sensitivity).append(". ").append(note).append("\n");
        if (qwenActive)    qwenCoreSec.append("* `").append(className).append("` - Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
        if (anyGeminiActive) geminiCoreSec.append("- `").append(className).append("`: Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");

        if (llmsActive)     llmsTxtCore.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
        if (llmsFullActive) llmsFullTxtCore.append("### ").append(className).append("\n- **Sensitivity**: ").append(sensitivity).append("\n- **Note**: ").append(note).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### CORE FUNCTIONALITY: ").append(className).append("\n- **Sensitivity**: ").append(sensitivity).append("\n- **Note**: ").append(note).append("\n\n");
        if (windsurfActive)  windsurfCoreSection.append("* `").append(className).append("` - Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
        if (zedActive)       zedCoreSection.append("- `").append(className).append("`: Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
        if (mentatActive)    mentatCore.append("    {\"path\": \"").append(className).append("\", \"sensitivity\": \"").append(sensitivity).append("\", \"note\": \"").append(note).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"Core functionality (change with caution): ").append(className).append(" [sensitivity: ").append(sensitivity).append("]\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (core, sensitivity: ").append(sensitivity).append("): ").append(note).append("\n");
        if (granularActive)  appendToGranular(e, "Core Functionality", "- **Sensitivity**: " + sensitivity + "\n- **Note**: " + note);
    }

    private void appendPerformance(Element e, StringBuilder cursorPerfSec, StringBuilder claudePerfSec,
                                   StringBuilder codexPerfSec, StringBuilder copilotPerfSec,
                                   StringBuilder qwenPerfSec, StringBuilder geminiPerfSec) {
        AIPerformance perf = e.getAnnotation(AIPerformance.class);
        if (perf == null) return;
        String className = ElementNaming.elementPath(e);
        String constraint = perf.constraint();

        if (cursorActive)  cursorPerfSec.append("* `").append(className).append("` - ").append(constraint).append("\n");
        if (claudeActive)  claudePerfSec.append("    <element path=\"").append(className).append("\">\n      <constraint>").append(constraint).append("</constraint>\n    </element>\n");
        if (codexActive)   codexPerfSec.append("- **").append(className).append("**: ").append(constraint).append("\n");
        if (copilotActive) copilotPerfSec.append("- `").append(className).append("`: ").append(constraint).append("\n");
        if (qwenActive)    qwenPerfSec.append("* `").append(className).append("` - ").append(constraint).append("\n");
        if (anyGeminiActive) geminiPerfSec.append("- `").append(className).append("`: ").append(constraint).append("\n");

        if (llmsActive)     llmsTxtPerformance.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(constraint).append("\n");
        if (llmsFullActive) llmsFullTxtPerformance.append("### ").append(className).append("\n- **Constraint**: ").append(constraint).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### PERFORMANCE CONSTRAINTS: ").append(className).append("\n- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n- **Constraint**: ").append(constraint).append("\n\n");
        if (windsurfActive)  windsurfPerfSection.append("* `").append(className).append("` - ").append(constraint).append("\n");
        if (zedActive)       zedPerfSection.append("- `").append(className).append("`: ").append(constraint).append("\n");
        if (mentatActive)    mentatPerf.append("    {\"path\": \"").append(className).append("\", \"constraint\": \"").append(constraint).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"Performance constraint for ").append(className).append(": ").append(constraint).append("\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (performance): ").append(constraint).append("\n");
        if (granularActive)  appendToGranular(e, "Performance Constraints", "- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n- **Constraint**: " + constraint);
    }

    private void appendContract(Element e, StringBuilder cursorContractSec, StringBuilder claudeContractSec,
                                StringBuilder codexContractSec, StringBuilder copilotContractSec,
                                StringBuilder qwenContractSec, StringBuilder geminiContractSec) {
        AIContract contract = e.getAnnotation(AIContract.class);
        if (contract == null) return;
        String className = ElementNaming.elementPath(e);
        String reason = contract.reason();

        if (cursorActive)  cursorContractSec.append("* `").append(className).append("` - ").append(reason).append("\n");
        if (claudeActive)  claudeContractSec.append("    <element path=\"").append(className).append("\">\n      <reason>").append(reason).append("</reason>\n    </element>\n");
        if (codexActive)   codexContractSec.append("- **").append(className).append("**: ").append(reason).append("\n");
        if (copilotActive) copilotContractSec.append("- `").append(className).append("` - ").append(reason).append("\n");
        if (qwenActive)    qwenContractSec.append("* `").append(className).append("` - ").append(reason).append("\n");
        if (anyGeminiActive) geminiContractSec.append("- `").append(className).append("`: ").append(reason).append("\n");

        if (llmsActive)     llmsTxtContract.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(reason).append("\n");
        if (llmsFullActive) llmsFullTxtContract.append("### ").append(className).append("\n- **Reason**: ").append(reason).append("\n\n");

        if (aiderConvActive) aiderConventions.append("#### CONTRACT: ").append(className).append("\n- **Constraint**: Signature is frozen. Do not change method names, parameter types, return types, or checked exceptions.\n- **Reason**: ").append(reason).append("\n\n");
        if (windsurfActive)  windsurfContractSection.append("* `").append(className).append("` - ").append(reason).append("\n");
        if (zedActive)       zedContractSection.append("- `").append(className).append("`: ").append(reason).append("\n");
        if (mentatActive)    mentatContract.append("    {\"path\": \"").append(className).append("\", \"reason\": \"").append(reason).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"Contract-frozen signature for ").append(className).append(": do not change method name, parameters, return type, or checked exceptions\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (contract): signature frozen — ").append(reason).append("\n");
        if (granularActive)  appendToGranular(e, "Contract-Frozen Signature", "- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.\n- **Reason**: " + reason);
    }

    private void appendTestDriven(Element e, StringBuilder cursorTestDrivenSec, StringBuilder claudeTestDrivenSec,
                                  StringBuilder codexTestDrivenSec, StringBuilder copilotTestDrivenSec,
                                  StringBuilder qwenTestDrivenSec, StringBuilder geminiTestDrivenSec) {
        AITestDriven td = e.getAnnotation(AITestDriven.class);
        if (td == null) return;
        String className = ElementNaming.elementPath(e);
        int coverageGoal = td.coverageGoal();
        String testLocation = td.testLocation();
        String mockPolicy = td.mockPolicy();

        StringBuilder frameworks = new StringBuilder();
        for (AITestDriven.Framework f : td.framework()) {
            if (frameworks.length() > 0) frameworks.append(", ");
            frameworks.append(f.name());
        }
        String frameworksStr = frameworks.toString();

        String locationHint = testLocation.isEmpty() ? "" : " Test file: " + testLocation + ".";
        String mockHint = mockPolicy.isEmpty() ? "" : " Mock policy: " + mockPolicy + ".";
        String summary = "Coverage goal: " + coverageGoal + "%. Framework: " + frameworksStr + "." + locationHint + mockHint;

        if (cursorActive)  cursorTestDrivenSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (claudeActive) {
            claudeTestDrivenSec.append("    <element path=\"").append(className).append("\">\n");
            claudeTestDrivenSec.append("      <coverage_goal>").append(coverageGoal).append("</coverage_goal>\n");
            claudeTestDrivenSec.append("      <frameworks>").append(frameworksStr).append("</frameworks>\n");
            if (!testLocation.isEmpty())
                claudeTestDrivenSec.append("      <test_location>").append(testLocation).append("</test_location>\n");
            if (!mockPolicy.isEmpty())
                claudeTestDrivenSec.append("      <mock_policy>").append(mockPolicy).append("</mock_policy>\n");
            claudeTestDrivenSec.append("    </element>\n");
        }
        if (codexActive)   codexTestDrivenSec.append("- **").append(className).append("**: ").append(summary).append("\n");
        if (copilotActive) copilotTestDrivenSec.append("- `").append(className).append("` - ").append(summary).append("\n");
        if (qwenActive)    qwenTestDrivenSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (anyGeminiActive) geminiTestDrivenSec.append("- `").append(className).append("`: ").append(summary).append("\n");

        if (llmsActive)     llmsTxtTestDriven.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtTestDriven.append("### ").append(className).append("\n- **Coverage Goal**: ").append(coverageGoal).append("%\n- **Frameworks**: ").append(frameworksStr).append("\n");

        if (llmsFullActive && !testLocation.isEmpty())
            llmsFullTxtTestDriven.append("- **Test Location**: ").append(testLocation).append("\n");
        if (llmsFullActive && !mockPolicy.isEmpty())
            llmsFullTxtTestDriven.append("- **Mock Policy**: ").append(mockPolicy).append("\n");
        if (llmsFullActive) llmsFullTxtTestDriven.append("\n");

        if (aiderConvActive) aiderConventions.append("#### TEST-DRIVEN: ").append(className).append("\n- **Rule**: Changes MUST be accompanied by test updates.\n- **Coverage Goal**: ").append(coverageGoal).append("%\n- **Frameworks**: ").append(frameworksStr).append("\n");
        if (windsurfActive)  windsurfTestDrivenSection.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)       zedTestDrivenSection.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (mentatActive)    mentatTestDriven.append("    {\"path\": \"").append(className).append("\", \"coverageGoal\": ").append(coverageGoal).append(", \"frameworks\": \"").append(frameworksStr).append("\"},\n");
        if (sweepActive)     sweepRules.append("  - \"Test-driven requirement for ").append(className).append(": ").append(summary).append("\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (test-driven): ").append(summary).append("\n");
        if (granularActive)  appendToGranular(e, "Test-Driven Requirements", "- **Rule**: Changes MUST be accompanied by a matching test update.\n- **Coverage Goal**: " + coverageGoal + "%\n- **Frameworks**: " + frameworksStr + (testLocation.isEmpty() ? "" : "\n- **Test Location**: " + testLocation) + (mockPolicy.isEmpty() ? "" : "\n- **Mock Policy**: " + mockPolicy));
    }

    /**
     * Helper for the v0.9.5 appenders — emits the standard cursor/codex/copilot/qwen/gemini
     * one-line entry for an element + summary string. Each platform uses its own bullet style.
     */
    private void appendCommonRow(StringBuilder cursorSec, StringBuilder codexSec,
                                  StringBuilder copilotSec, StringBuilder qwenSec,
                                  StringBuilder geminiSec, String className, CharSequence summary) {
        if (cursorActive)  cursorSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (codexActive)   codexSec.append("- **").append(className).append("**: ").append(summary).append("\n");
        if (copilotActive) copilotSec.append("- `").append(className).append("` - ").append(summary).append("\n");
        if (qwenActive)    qwenSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (anyGeminiActive) geminiSec.append("- `").append(className).append("`: ").append(summary).append("\n");
    }

    private void appendThreadSafe(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                  StringBuilder codexSec, StringBuilder copilotSec,
                                  StringBuilder qwenSec, StringBuilder geminiSec) {
        AIThreadSafe ts = e.getAnnotation(AIThreadSafe.class);
        if (ts == null) return;
        String className = ElementNaming.elementPath(e);
        String strategy = ts.strategy().name();
        String note = ts.note();
        String summary = "Strategy: " + strategy + (note.isEmpty() ? "" : ". Note: " + note);

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <strategy>").append(strategy).append("</strategy>\n");
            if (!note.isEmpty()) claudeSec.append("      <note>").append(note).append("</note>\n");
            claudeSec.append("    </element>\n");
        }

        if (llmsActive)     llmsTxtThreadSafe.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtThreadSafe.append("### ").append(className).append("\n- **Strategy**: ").append(strategy).append("\n");
            if (!note.isEmpty()) llmsFullTxtThreadSafe.append("- **Note**: ").append(note).append("\n");
            llmsFullTxtThreadSafe.append("\n");
        }

        if (aiderConvActive)   aiderConventions.append("#### THREAD-SAFE: ").append(className).append("\n- **Strategy**: ").append(strategy).append("\n").append(note.isEmpty() ? "" : "- **Note**: " + note + "\n").append("\n");
        if (windsurfActive)    windsurfRules.append("* `").append(className).append("` (thread-safe) - ").append(summary).append("\n");
        if (zedActive)         zedRules.append("- `").append(className).append("` (thread-safe): ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (thread-safe): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Thread-Safety Guarantee", "- **Strategy**: " + strategy + (note.isEmpty() ? "" : "\n- **Note**: " + note));
    }

    private void appendImmutable(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                 StringBuilder codexSec, StringBuilder copilotSec,
                                 StringBuilder qwenSec, StringBuilder geminiSec) {
        AIImmutable im = e.getAnnotation(AIImmutable.class);
        if (im == null) return;
        String className = ElementNaming.elementPath(e);
        String note = im.note();

        String summary = note.isEmpty() ? "immutable type" : note;
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <type path=\"").append(className).append("\">\n");
            if (!note.isEmpty()) claudeSec.append("      <note>").append(note).append("</note>\n");
            claudeSec.append("    </type>\n");
        }

        if (llmsActive)     llmsTxtImmutable.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): immutable type").append(note.isEmpty() ? "" : " — " + note).append("\n");
        if (llmsFullActive) {
            llmsFullTxtImmutable.append("### ").append(className).append("\n- Immutable type — never introduce non-final fields, setters, or mutating methods.\n");
            if (!note.isEmpty()) llmsFullTxtImmutable.append("- **Note**: ").append(note).append("\n");
            llmsFullTxtImmutable.append("\n");
        }

        if (aiderConvActive)   aiderConventions.append("#### IMMUTABLE: ").append(className).append("\n- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.\n").append(note.isEmpty() ? "" : "- **Note**: " + note + "\n").append("\n");
        if (windsurfActive)    windsurfRules.append("* `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : " - " + note).append("\n");
        if (zedActive)         zedRules.append("- `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : ": " + note).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : ": " + note).append("\n");
        if (granularActive)    appendToGranular(e, "Immutable Type", "- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods." + (note.isEmpty() ? "" : "\n- **Note**: " + note));
    }

    private void appendDeprecated(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                  StringBuilder codexSec, StringBuilder copilotSec,
                                  StringBuilder qwenSec, StringBuilder geminiSec) {
        AIDeprecated dep = e.getAnnotation(AIDeprecated.class);
        if (dep == null) return;
        String className = ElementNaming.elementPath(e);
        String replacedBy = dep.replacedBy();
        String migrationGuide = dep.migrationGuide();
        String deadline = dep.deadline();

        StringBuilder summary = new StringBuilder();
        if (!replacedBy.isEmpty()) summary.append("Replaced by: `").append(replacedBy).append("`. ");
        summary.append(migrationGuide);
        if (!deadline.isEmpty()) summary.append(" (Removal deadline: ").append(deadline).append(")");

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n");
            if (!replacedBy.isEmpty()) claudeSec.append("      <replaced_by>").append(replacedBy).append("</replaced_by>\n");
            claudeSec.append("      <migration_guide>").append(migrationGuide).append("</migration_guide>\n");
            if (!deadline.isEmpty()) claudeSec.append("      <deadline>").append(deadline).append("</deadline>\n");
            claudeSec.append("    </element>\n");
        }

        if (llmsActive)     llmsTxtDeprecated.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtDeprecated.append("### ").append(className).append("\n");
            if (!replacedBy.isEmpty()) llmsFullTxtDeprecated.append("- **Replaced by**: ").append(replacedBy).append("\n");
            llmsFullTxtDeprecated.append("- **Migration**: ").append(migrationGuide).append("\n");
            if (!deadline.isEmpty()) llmsFullTxtDeprecated.append("- **Deadline**: ").append(deadline).append("\n");
            llmsFullTxtDeprecated.append("\n");
        }

        if (aiderConvActive)   aiderConventions.append("#### DEPRECATED: ").append(className).append("\n- **Status**: Scheduled for removal. Do not extend.\n").append(replacedBy.isEmpty() ? "" : "- **Replaced by**: " + replacedBy + "\n").append("- **Migration**: ").append(migrationGuide).append("\n").append(deadline.isEmpty() ? "" : "- **Deadline**: " + deadline + "\n").append("\n");
        if (windsurfActive)    windsurfRules.append("* `").append(className).append("` (deprecated) - ").append(summary).append("\n");
        if (zedActive)         zedRules.append("- `").append(className).append("` (deprecated): ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (deprecated): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Deprecated — Migrate Callers", (replacedBy.isEmpty() ? "" : "- **Replaced by**: " + replacedBy + "\n") + "- **Migration**: " + migrationGuide + (deadline.isEmpty() ? "" : "\n- **Deadline**: " + deadline));
    }

    private void appendObservability(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                     StringBuilder codexSec, StringBuilder copilotSec,
                                     StringBuilder qwenSec, StringBuilder geminiSec) {
        AIObservability obs = e.getAnnotation(AIObservability.class);
        if (obs == null) return;
        String className = ElementNaming.elementPath(e);
        String[] metrics = obs.metrics();
        String[] traces = obs.traces();
        String[] logs = obs.logs();
        String note = obs.note();

        StringBuilder summary = new StringBuilder();
        if (metrics.length > 0) summary.append("Metrics: ").append(String.join(", ", metrics)).append(". ");
        if (traces.length > 0)  summary.append("Traces: ").append(String.join(", ", traces)).append(". ");
        if (logs.length > 0)    summary.append("Logs: ").append(String.join(", ", logs)).append(". ");
        if (!note.isEmpty())    summary.append("Note: ").append(note);

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n");
            for (String m : metrics) claudeSec.append("      <metric>").append(m).append("</metric>\n");
            for (String t : traces)  claudeSec.append("      <trace>").append(t).append("</trace>\n");
            for (String l : logs)    claudeSec.append("      <log>").append(l).append("</log>\n");
            if (!note.isEmpty()) claudeSec.append("      <note>").append(note).append("</note>\n");
            claudeSec.append("    </element>\n");
        }

        if (llmsActive)     llmsTxtObservability.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtObservability.append("### ").append(className).append("\n");
            if (metrics.length > 0) llmsFullTxtObservability.append("- **Metrics**: ").append(String.join(", ", metrics)).append("\n");
            if (traces.length > 0)  llmsFullTxtObservability.append("- **Traces**: ").append(String.join(", ", traces)).append("\n");
            if (logs.length > 0)    llmsFullTxtObservability.append("- **Logs**: ").append(String.join(", ", logs)).append("\n");
            if (!note.isEmpty())    llmsFullTxtObservability.append("- **Note**: ").append(note).append("\n");
            llmsFullTxtObservability.append("\n");
        }

        if (aiderConvActive)   aiderConventions.append("#### OBSERVABILITY: ").append(className).append("\n- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard/alert.\n- **Details**: ").append(summary).append("\n\n");
        if (windsurfActive)    windsurfRules.append("* `").append(className).append("` (observability) - ").append(summary).append("\n");
        if (zedActive)         zedRules.append("- `").append(className).append("` (observability): ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (observability): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Observability Instrumentation", "- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.\n- **Details**: " + summary);
    }

    private void appendRegulation(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                  StringBuilder codexSec, StringBuilder copilotSec,
                                  StringBuilder qwenSec, StringBuilder geminiSec) {
        AIRegulation reg = e.getAnnotation(AIRegulation.class);
        if (reg == null) return;
        String className = ElementNaming.elementPath(e);
        String standard = reg.standard();
        String clause = reg.clause();
        String description = reg.description();

        String summary = standard + (clause.isEmpty() ? "" : " " + clause) + " — " + description;

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <standard>").append(standard).append("</standard>\n");
            if (!clause.isEmpty()) claudeSec.append("      <clause>").append(clause).append("</clause>\n");
            claudeSec.append("      <description>").append(description).append("</description>\n    </element>\n");
        }

        if (llmsActive)     llmsTxtRegulation.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtRegulation.append("### ").append(className).append("\n- **Standard**: ").append(standard).append("\n");
            if (!clause.isEmpty()) llmsFullTxtRegulation.append("- **Clause**: ").append(clause).append("\n");
            llmsFullTxtRegulation.append("- **Description**: ").append(description).append("\n\n");
        }

        if (aiderConvActive)   aiderConventions.append("#### REGULATORY: ").append(className).append("\n- **Standard**: ").append(standard).append("\n").append(clause.isEmpty() ? "" : "- **Clause**: " + clause + "\n").append("- **Description**: ").append(description).append("\n\n");
        if (windsurfActive)    windsurfRules.append("* `").append(className).append("` (regulation) - ").append(summary).append("\n");
        if (zedActive)         zedRules.append("- `").append(className).append("` (regulation): ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (regulation): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Regulatory Compliance", "- **Standard**: " + standard + (clause.isEmpty() ? "" : "\n- **Clause**: " + clause) + "\n- **Description**: " + description);
    }

    private void appendToGranular(Element element, String title, String content) {
        Element owner = ElementNaming.owningElement(element);
        StringBuilder sb = elementRules.computeIfAbsent(owner, k -> new StringBuilder());
        if (sb.length() > 0) sb.append("\n");

        if (!owner.equals(element)) {
            ElementKind kind = element.getKind();
            String kindStr = (kind != null) ? kind.toString().toLowerCase(java.util.Locale.ROOT) : "element";
            sb.append("### Rules for ").append(kindStr).append(" ").append(element.getSimpleName()).append("\n");
        } else {
            sb.append("## ").append(title).append("\n");
        }
        sb.append(content).append("\n");
    }

    // -------------------------------------------------------------------------------------------
    // Initialization + final assembly
    // -------------------------------------------------------------------------------------------

    /**
     * Allocates per-platform builders. Builders for inactive services are left {@code null}; every
     * call site that touches them is gated behind the matching {@code *Active} flag (or its
     * {@code activeServices.contains(...)} equivalent in {@link #buildContentMap}), so no NPE
     * paths exist. Skipping the allocation saves the header-string copy plus the backing
     * {@code char[]} for each platform that the consumer hasn't opted into.
     */
    private void initBuilders() {
        int mainHint = mainBuilderHint();
        // Always-allocated main builders. cursor/claude/codex/copilot/qwen are touched in a few
        // ungated spots (appendContextHeaders + post-loop section appenders), so they stay eager.
        cursorRules = preSized("# AUTO-GENERATED AI RULES\n" + generatedHeader + "# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n", mainHint);
        claudeMd = preSized("<!-- " + generatedHeader.trim() + " -->\n<project_guardrails>\n  <locked_files>\n", mainHint);
        aiExclude = preSized("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# The following files are strictly excluded from AI context.\n", sectionHint(collector.ignore().size()));
        codexAgents = preSized("# AUTO-GENERATED AI RULES\n" + generatedHeader + "# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n", mainHint);
        copilot = preSized("# GitHub Copilot Instructions\n" + generatedHeader + "# AUTO-GENERATED BY VIBETAGS. Do not edit manually.\n\n## Locked Files — DO NOT MODIFY\nDo not suggest changes to the following files:\n\n", mainHint);
        qwenMd = preSized("# PROJECT CONTEXT\n" + generatedHeader + "# AUTO-GENERATED BY VIBETAGS\n\n## LOCKED FILES (DO NOT EDIT)\n", mainHint);

        cursorIgnoreSection = new StringBuilder("\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n");

        // llms.txt and llms-full.txt — only allocate when the consumer opted in. Each holds many
        // KB of headers + per-section preambles, so skipping is worthwhile.
        if (llmsActive || llmsFullActive) {
            int llmsHint = Math.max(mainHint, mainHint * 2 / 3 + 4096);
            if (llmsActive) {
                llmsTxt = preSized("# " + projectName + "\n\n" +
                    "> AI guardrail rules generated from source annotations by VibeTags.\n\n" +
                    "AI tools reading this file should respect the guardrails defined below. " +
                    "These rules were extracted from Java source annotations at compile time " +
                    "and apply to all AI assistants including Windsurf Cascade, Cursor, Claude, " +
                    "GitHub Copilot, and Gemini.\n\n" +
                    "## Locked Files\n", llmsHint);
                llmsTxtContext  = new StringBuilder("\n## Contextual Rules\n");
                llmsTxtAudit    = new StringBuilder("\n## Security Audit Requirements\n");
                llmsTxtIgnore   = new StringBuilder("\n## Ignored Elements\n");
                llmsTxtDraft    = new StringBuilder("\n## Implementation Tasks\n");
                llmsTxtPrivacy  = new StringBuilder("\n## PII / Privacy Guardrails\n");
                llmsTxtCore     = new StringBuilder("\n## 🧠 Core Functionality\n");
                llmsTxtPerformance = new StringBuilder("\n## ⚡ Performance Constraints\n");
                llmsTxtContract    = new StringBuilder("\n## 🔐 Contract-Frozen Signatures\n");
                llmsTxtTestDriven  = new StringBuilder("\n## 🧪 Test-Driven Requirements\n");
                llmsTxtThreadSafe    = new StringBuilder("\n## 🧵 Thread-Safe by Design\n");
                llmsTxtImmutable     = new StringBuilder("\n## ❄️ Immutable Types\n");
                llmsTxtDeprecated    = new StringBuilder("\n## ⚠️ Deprecated Elements\n");
                llmsTxtObservability = new StringBuilder("\n## 📡 Observability Instrumentation\n");
                llmsTxtRegulation    = new StringBuilder("\n## 📜 Regulatory Compliance\n");
                llmsTxtParallelTests     = new StringBuilder("\n## Strict Test Isolation\n");
                llmsTxtLegacyBridge      = new StringBuilder("\n## Legacy Compatibility Bridge\n");
                llmsTxtArchitecture      = new StringBuilder("\n## Architectural Boundary Constraints\n");
                llmsTxtPublicApi         = new StringBuilder("\n## Public API Surface Protection\n");
                llmsTxtStrictExceptions  = new StringBuilder("\n## Strict Exception Handling\n");
                llmsTxtStrictTypes       = new StringBuilder("\n## Strict Type Safety\n");
                llmsTxtInternationalized = new StringBuilder("\n## Internationalization Mandate\n");
                llmsTxtStrictClasspath   = new StringBuilder("\n## Strict Classpath Integrity\n");
                llmsTxtSchemaSafe        = new StringBuilder("\n## Schema & Serialization Safety\n");
                llmsTxtIdempotent        = new StringBuilder("\n## ♻️ Idempotency Guarantees\n");
                llmsTxtFeatureFlag       = new StringBuilder("\n## 🚩 Feature Flag Gated Code\n");
                llmsTxtSecure            = new StringBuilder("\n## 🔐 Security-Critical Code\n");
            }
            if (llmsFullActive) {
                llmsFullTxt = preSized("# " + projectName + " — AI Guardrail Rules\n" +
                    "> Complete AI guardrail configuration generated from source annotations by VibeTags.\n\n" +
                    "This document contains the full set of AI guardrail rules for this project. " +
                    "AI tools with large context windows (such as Windsurf Cascade, Claude 4.6, or Gemini 1.5 Pro) " +
                    "may load this file directly instead of fetching individual documentation pages.\n\n" +
                    "## Locked Files (Do Not Edit)\n" +
                    "The following files are locked. AI tools MUST NOT propose modifications to them.\n\n", llmsHint);
                llmsFullTxtContext     = new StringBuilder("\n## Contextual Rules\nThese files have specific context and focus areas for AI assistance.\n\n");
                llmsFullTxtAudit       = new StringBuilder("\n## Mandatory Security Audit Requirements\nWhen writing or modifying the following files, perform a security audit for the listed vulnerabilities before displaying any code to the user.\n\n");
                llmsFullTxtIgnore      = new StringBuilder("\n## Ignored Elements\nThe following elements must be completely excluded from AI context. Treat them as non-existent.\n\n");
                llmsFullTxtDraft       = new StringBuilder("\n## Implementation Tasks\nThe following elements are in draft mode and need implementation.\n\n");
                llmsFullTxtPrivacy     = new StringBuilder("\n## PII / Privacy Guardrails\nNever include runtime values of the following elements in logs, console output, external API calls, test fixtures, or mock data.\n\n");
                llmsFullTxtCore        = new StringBuilder("\n## 🧠 Core Functionality\nThe following elements are well-tested core functionality. Make changes with extreme caution.\n\n");
                llmsFullTxtPerformance = new StringBuilder("\n## ⚡ Performance Constraints\nThe following elements are on a hot-path and have strict time/space complexity constraints.\n\n");
                llmsFullTxtContract    = new StringBuilder("\n## 🔐 Contract-Frozen Signatures\nThe following elements have frozen public API signatures. Internal implementation may be changed, but you MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
                llmsFullTxtTestDriven  = new StringBuilder("\n## 🧪 Test-Driven Requirements\nThe following elements require a matching test update whenever their logic is modified. Changes without tests are incomplete.\n\n");
                llmsFullTxtThreadSafe    = new StringBuilder("\n## 🧵 Thread-Safe by Design\nThese elements are explicitly designed to be thread-safe via the named strategy. Preserve the synchronization invariant on every change.\n\n");
                llmsFullTxtImmutable     = new StringBuilder("\n## ❄️ Immutable Types\nThe following types are immutable. Never introduce non-final fields, setters, or mutating methods.\n\n");
                llmsFullTxtDeprecated    = new StringBuilder("\n## ⚠️ Deprecated Elements\nThe following elements are deprecated. Suggest migration to the named replacement for any caller and do not extend them.\n\n");
                llmsFullTxtObservability = new StringBuilder("\n## 📡 Observability Instrumentation\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on.\n\n");
                llmsFullTxtRegulation    = new StringBuilder("\n## 📜 Regulatory Compliance\nThe following elements implement specific regulatory clauses. Document compliance impact for every change and never weaken the requirement.\n\n");
                llmsFullTxtParallelTests     = new StringBuilder("\n## Strict Test Isolation\nAI tools must enforce strict isolation when generating or modifying tests for these elements.\n\n");
                llmsFullTxtLegacyBridge      = new StringBuilder("\n## Legacy Compatibility Bridge\nThese elements are legacy or compatibility bridges. Do not restructure or modernize them.\n\n");
                llmsFullTxtArchitecture      = new StringBuilder("\n## Architectural Boundary Constraints\nStrict architectural layering must be respected. No illegal references or imports.\n\n");
                llmsFullTxtPublicApi         = new StringBuilder("\n## Public API Surface Protection\nThese elements expose public API surfaces. Preserve signatures, Javadocs, and backward compatibility.\n\n");
                llmsFullTxtStrictExceptions  = new StringBuilder("\n## Strict Exception Handling\nPrecise and robust exception handling must be enforced. No catching or throwing generic Exception.\n\n");
                llmsFullTxtStrictTypes       = new StringBuilder("\n## Strict Type Safety\nType safety must be strictly preserved. Loose or erased types are prohibited.\n\n");
                llmsFullTxtInternationalized = new StringBuilder("\n## Internationalization Mandate\nUser-facing strings must not be hardcoded; resolve them via localized resources.\n\n");
                llmsFullTxtStrictClasspath   = new StringBuilder("\n## Strict Classpath Integrity\nDynamic runtime class loading and reflections are strictly prohibited.\n\n");
                llmsFullTxtSchemaSafe        = new StringBuilder("\n## Schema & Serialization Safety\nSchema and serialization compatibility must be strictly preserved.\n\n");
                llmsFullTxtIdempotent        = new StringBuilder("\n## ♻️ Idempotency Guarantees\nThese operations are idempotent — calling multiple times must produce the same result as calling once.\n\n");
                llmsFullTxtFeatureFlag       = new StringBuilder("\n## 🚩 Feature Flag Gated Code\nThese elements are gated behind a feature flag. Preserve the flag check and handle both enabled and disabled code paths.\n\n");
                llmsFullTxtSecure            = new StringBuilder("\n## 🔐 Security-Critical Code\nThese elements are security-critical. Do not weaken security properties. Every change requires security review.\n\n");
            }
        }

        // Per-platform ignore-list files — every append site is gated by the matching *IgnoreFileActive flag.
        if (cursorIgnoreFileActive)  cursorIgnoreFile  = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Cursor-specific exclusion list.\n");
        if (claudeIgnoreFileActive)  claudeIgnoreFile  = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Claude-specific exclusion list.\n");
        if (copilotIgnoreFileActive) copilotIgnoreFile = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Copilot-specific exclusion list.\n");
        if (qwenIgnoreFileActive)    qwenIgnoreFile    = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Qwen-specific exclusion list.\n");

        if (aiderConvActive)   aiderConventions = new StringBuilder("# " + projectName + " CONVENTIONS\n" + generatedHeader + "# AUTO-GENERATED BY VIBETAGS\n\nThis file contains project-specific coding conventions and AI guardrails extracted from source annotations.\n\n");
        if (aiderIgnoreActive) aiderIgnore      = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Aider-specific exclusion list.\n");

        if (windsurfActive) {
            windsurfRules = preSized("# AUTO-GENERATED AI RULES\n" + generatedHeader + "# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n", mainHint);
            windsurfIgnoreSection = new StringBuilder("\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n");
            windsurfDraftSection  = new StringBuilder("\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n");
            windsurfPrivacySection = new StringBuilder("\n## 🔒 PII / PRIVACY GUARDRAILS\nNever include runtime values of the following in logs, console output, external API calls, test fixtures, or mock data.\n\n");
            windsurfCoreSection     = new StringBuilder("\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
            windsurfPerfSection         = new StringBuilder("\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) or worse complexity. Always reason about time/space before proposing changes.\n\n");
            windsurfContractSection     = new StringBuilder("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
            windsurfTestDrivenSection   = new StringBuilder("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nAI MUST NOT propose changes to the following elements without also providing the matching test code.\n\n");
        }

        if (zedActive) {
            zedRules = preSized("# AUTO-GENERATED AI RULES\n" + generatedHeader + "# Do not edit manually.\n\n## Locked Files (Do Not Modify)\n", mainHint);
            zedIgnoreSection  = new StringBuilder("\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n");
            zedDraftSection   = new StringBuilder("\n## Implementation Tasks\nThe following elements are drafts that need implementation:\n\n");
            zedPrivacySection = new StringBuilder("\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs runtime values of these elements:\n\n");
            zedCoreSection     = new StringBuilder("\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n");
            zedPerfSection         = new StringBuilder("\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n");
            zedContractSection     = new StringBuilder("\n## Contract-Frozen Signatures\nInternal logic may be changed; never alter method names, parameter types, order, return types, or checked exceptions:\n\n");
            zedTestDrivenSection   = new StringBuilder("\n## Test-Driven Requirements\nChanges to the following elements must be accompanied by matching test code:\n\n");
        }

        if (codyIgnoreActive)       codyIgnoreFile       = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Cody-specific exclusion list.\n");
        if (supermavenIgnoreActive) supermavenIgnoreFile = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Supermaven-specific exclusion list.\n");
        if (doubleIgnoreActive)     doubleIgnoreFile     = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Double.bot-specific exclusion list.\n");
        if (codeiumIgnoreActive)    codeiumIgnoreFile    = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Codeium-specific exclusion list.\n");
        if (antigravityIgnoreActive) antigravityIgnoreFile = new StringBuilder("# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader + "# Antigravity AI-specific exclusion list.\n");

        if (mentatActive) {
            mentatLocked    = new StringBuilder();
            mentatAudit     = new StringBuilder();
            mentatPrivacy   = new StringBuilder();
            mentatCore      = new StringBuilder();
            mentatPerf      = new StringBuilder();
            mentatContract  = new StringBuilder();
            mentatIgnore    = new StringBuilder();
            mentatDraft     = new StringBuilder();
            mentatTestDriven = new StringBuilder();
        }
        if (sweepActive) {
            sweepRules = new StringBuilder();
        }
        if (plandexActive) {
            plandexLocked = new StringBuilder();
            plandexAudit  = new StringBuilder();
            plandexPrivacy = new StringBuilder();
        }
        if (interpreterActive) {
            interpreterRules = new StringBuilder();
        }
    }

    private Map<String, String> buildContentMap(StringBuilder geminiMd,
                                                String clineContent,
                                                String junieContent) {
        Map<String, String> contentByService = new LinkedHashMap<>();
        if (activeServices.contains("cursor"))    contentByService.put("cursor",    cursorRules.toString());
        if (activeServices.contains("cline") && clineContent != null) contentByService.put("cline", clineContent);
        if (activeServices.contains("junie") && junieContent != null) contentByService.put("junie", junieContent);
        if (activeServices.contains("qwen"))      contentByService.put("qwen",      qwenMd.toString());
        if (activeServices.contains("claude"))    contentByService.put("claude",    claudeMd.toString());
        if (activeServices.contains("gemini"))    contentByService.put("gemini",    geminiMd.toString());
        if (activeServices.contains("codex"))     contentByService.put("codex",     codexAgents.toString());
        if (activeServices.contains("copilot"))   contentByService.put("copilot",   copilot.toString());
        if (activeServices.contains("llms"))      contentByService.put("llms",      llmsTxt.toString());
        if (activeServices.contains("llms_full")) contentByService.put("llms_full", llmsFullTxt.toString());

        if (activeServices.contains("aiexclude") && (activeServices.contains("gemini") || activeServices.contains("codex"))) {
            contentByService.put("aiexclude", aiExclude.toString());
        }

        if (activeServices.contains("cursor_ignore"))  contentByService.put("cursor_ignore",  cursorIgnoreFile.toString());
        if (activeServices.contains("claude_ignore"))  contentByService.put("claude_ignore",  claudeIgnoreFile.toString());
        if (activeServices.contains("copilot_ignore")) contentByService.put("copilot_ignore", copilotIgnoreFile.toString());
        if (activeServices.contains("qwen_ignore"))    contentByService.put("qwen_ignore",    qwenIgnoreFile.toString());

        if (activeServices.contains("codex")) {
            contentByService.put("codex_config", "# " + generatedHeader.trim() + "\n[project]\nmodel = \"o3-mini\"\napproval_policy = \"on-request\"\n");
            contentByService.put("codex_rules",
                "# " + generatedHeader.trim() + "\n# VibeTags: Starlark Command Permissions\n\n" +
                "prefix_rule(\"ls\", \"allow\")\n" +
                "prefix_rule(\"cat\", \"allow\")\n" +
                "prefix_rule(\"grep\", \"allow\")\n" +
                "prefix_rule(\"mvn\", \"prompt\")\n" +
                "prefix_rule(\"npm\", \"prompt\")\n" +
                "prefix_rule(\"git\", \"prompt\")\n" +
                "prefix_rule(\"rm\", \"prompt\")\n");
        }
        if (activeServices.contains("qwen")) {
            contentByService.put("qwen_settings", "{\n  \"project\": {\n    \"model\": \"qwen3-coder-plus\",\n    \"mcp\": {\n      \"enabled\": true\n    }\n  }\n}\n");
            contentByService.put("qwen_refactor", "# /refactor command\n# AUTO-GENERATED BY VIBETAGS\n\nRefactor the current selection to improve maintainability and performance while strictly following the project's contextual rules in QWEN.md.\n");
        }

        if (activeServices.contains("aider_conventions")) contentByService.put("aider_conventions", aiderConventions.toString());
        if (activeServices.contains("aider_ignore"))      contentByService.put("aider_ignore",      aiderIgnore.toString());

        if (activeServices.contains("windsurf"))          contentByService.put("windsurf",          windsurfRules.toString());
        if (activeServices.contains("zed"))               contentByService.put("zed",               zedRules.toString());
        if (activeServices.contains("cody_ignore"))       contentByService.put("cody_ignore",       codyIgnoreFile.toString());
        if (activeServices.contains("supermaven_ignore")) contentByService.put("supermaven_ignore", supermavenIgnoreFile.toString());
        if (activeServices.contains("cody"))              contentByService.put("cody",              buildCodyConfig());

        if (activeServices.contains("double_ignore"))      contentByService.put("double_ignore",      doubleIgnoreFile.toString());
        if (activeServices.contains("codeium_ignore"))     contentByService.put("codeium_ignore",     codeiumIgnoreFile.toString());
        if (activeServices.contains("antigravity_ignore")) contentByService.put("antigravity_ignore", antigravityIgnoreFile.toString());
        if (activeServices.contains("gemini_md"))          contentByService.put("gemini_md",          geminiMd.toString());
        if (activeServices.contains("mentat"))             contentByService.put("mentat",             buildMentatConfig());
        if (activeServices.contains("sweep"))           contentByService.put("sweep",           buildSweepConfig());
        if (activeServices.contains("plandex"))         contentByService.put("plandex",         buildPlandexConfig());
        if (activeServices.contains("interpreter"))     contentByService.put("interpreter",     buildInterpreterProfile());

        return contentByService;
    }

    private String buildCodyConfig() {
        return "{\n" +
            "  \"customCommands\": [\n" +
            "    {\n" +
            "      \"name\": \"vibetags-review\",\n" +
            "      \"description\": \"Review code following VibeTags guardrails\",\n" +
            "      \"prompt\": \"Review the selected code for compliance with the project AI guardrails. Check for violations of locked files, PII handling rules, performance constraints, and security requirements as defined in the project annotations.\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"vibetags-audit\",\n" +
            "      \"description\": \"Security audit following VibeTags @AIAudit rules\",\n" +
            "      \"prompt\": \"Perform a security audit of the selected code. Check for SQL injection, thread safety issues, and other vulnerabilities flagged in @AIAudit annotations.\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";
    }

    private String buildMentatConfig() {
        StringBuilder sb = new StringBuilder("{\n  \"_generated_by\": \"VibeTags\",\n  \"rules\": {\n");
        appendJsonSection(sb, "locked_files",   mentatLocked);
        appendJsonSection(sb, "audit",          mentatAudit);
        appendJsonSection(sb, "privacy",        mentatPrivacy);
        appendJsonSection(sb, "core",           mentatCore);
        appendJsonSection(sb, "performance",    mentatPerf);
        appendJsonSection(sb, "contract",       mentatContract);
        appendJsonSection(sb, "ignored",        mentatIgnore);
        appendJsonSection(sb, "draft",          mentatDraft);
        appendJsonSection(sb, "test_driven",    mentatTestDriven);
        sb.append("  }\n}\n");
        return sb.toString();
    }

    private static void appendJsonSection(StringBuilder out, String key, StringBuilder items) {
        if (items == null || items.length() == 0) return;
        // Trim trailing comma+newline from last item
        String body = items.toString();
        if (body.endsWith(",\n")) body = body.substring(0, body.length() - 2) + "\n";
        out.append("    \"").append(key).append("\": [\n").append(body).append("    ],\n");
    }

    private String buildSweepConfig() {
        StringBuilder sb = new StringBuilder(
            "# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader +
            "# Sweep AI code review rules. Do not edit manually.\n\n" +
            "rules:\n");
        if (sweepRules != null && sweepRules.length() > 0) {
            sb.append(sweepRules);
        } else {
            sb.append("  []\n");
        }
        return sb.toString();
    }

    private String buildPlandexConfig() {
        StringBuilder sb = new StringBuilder(
            "# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader +
            "# Plandex AI configuration. Do not edit manually.\n\n" +
            "guardrails:\n");
        if (plandexLocked != null && plandexLocked.length() > 0) {
            sb.append("  locked:\n").append(plandexLocked);
        }
        if (plandexAudit != null && plandexAudit.length() > 0) {
            sb.append("  audit:\n").append(plandexAudit);
        }
        if (plandexPrivacy != null && plandexPrivacy.length() > 0) {
            sb.append("  privacy:\n").append(plandexPrivacy);
        }
        return sb.toString();
    }

    private String buildInterpreterProfile() {
        StringBuilder sb = new StringBuilder(
            "# AUTO-GENERATED BY VIBETAGS\n" + generatedHeader +
            "# Open Interpreter VibeTags guardrail profile. Do not edit manually.\n\n" +
            "instructions: |\n");
        if (interpreterRules != null && interpreterRules.length() > 0) {
            sb.append("  ## Project Guardrails (Generated by VibeTags)\n\n");
            for (String line : interpreterRules.toString().split("\n")) {
                sb.append("  ").append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void appendParallelTests(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                     StringBuilder codexSec, StringBuilder copilotSec,
                                     StringBuilder qwenSec, StringBuilder geminiSec,
                                     StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Strict test isolation required. No shared mutable state or external resource conflicts.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <isolation>strict</isolation>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtParallelTests.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtParallelTests.append("### ").append(className).append("\n- Enforce strict test isolation and thread safety. Do not share mutable state between parallel test cases.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### TEST ISOLATION: ").append(className).append("\n- **Rule**: Strict test isolation required. No shared mutable state, specific order, or resource conflicts.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (test-isolation): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Strict Test Isolation", "- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.");
    }

    private void appendLegacyBridge(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                    StringBuilder codexSec, StringBuilder copilotSec,
                                    StringBuilder qwenSec, StringBuilder geminiSec,
                                    StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Legacy/compatibility bridge. Do not refactor structural patterns; only modify internal business logic as explicitly requested.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <refactor>prohibited</refactor>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtLegacyBridge.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtLegacyBridge.append("### ").append(className).append("\n- Legacy compatibility bridge. Do not refactor structural patterns. Only modify internal business logic as explicitly requested.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### LEGACY BRIDGE: ").append(className).append("\n- **Rule**: Do not restructure or modernize this class. Compatibility bridge.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (legacy-bridge): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Legacy Compatibility Bridge", "- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.");
    }

    private void appendArchitecture(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                    StringBuilder codexSec, StringBuilder copilotSec,
                                    StringBuilder qwenSec, StringBuilder geminiSec,
                                    StringBuilder windsurfSec, StringBuilder zedSec) {
        AIArchitecture arch = e.getAnnotation(AIArchitecture.class);
        if (arch == null) return;
        String className = ElementNaming.elementPath(e);
        String belongsTo = arch.belongsTo();
        String[] cannotRef = arch.cannotReference();
        String cannotRefStr = String.join(", ", cannotRef);
        String summary = "Belongs to layer: `" + belongsTo + "`" + (cannotRef.length > 0 ? ". Prohibited from referencing: [" + cannotRefStr + "]" : "");
        
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <belongs_to>").append(belongsTo).append("</belongs_to>\n");
            for (String r : cannotRef) {
                claudeSec.append("      <cannot_reference>").append(r).append("</cannot_reference>\n");
            }
            claudeSec.append("    </element>\n");
        }
        if (llmsActive)     llmsTxtArchitecture.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtArchitecture.append("### ").append(className).append("\n- **Belongs to Layer**: ").append(belongsTo).append("\n");
            if (cannotRef.length > 0) llmsFullTxtArchitecture.append("- **Prohibited References**: ").append(cannotRefStr).append("\n");
            llmsFullTxtArchitecture.append("\n");
        }
        if (aiderConvActive)   aiderConventions.append("#### ARCHITECTURE LAYER: ").append(className).append("\n- **Layer**: ").append(belongsTo).append("\n").append(cannotRef.length > 0 ? "- **Cannot Reference**: " + cannotRefStr + "\n" : "").append("\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (architecture): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Architectural Boundary Constraints", "- **Layer**: " + belongsTo + (cannotRef.length > 0 ? "\n- **Prohibited References**: " + cannotRefStr : ""));
    }

    private void appendPublicAPI(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                 StringBuilder codexSec, StringBuilder copilotSec,
                                 StringBuilder qwenSec, StringBuilder geminiSec,
                                 StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Public API surface. Preserve signature, Javadoc, backwards compatibility, and binary/source stability.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <api>public</api>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtPublicApi.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtPublicApi.append("### ").append(className).append("\n- Public API surface. Preserve signature, Javadoc, and behavior without breaking backwards compatibility.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### PUBLIC API: ").append(className).append("\n- **Rule**: Exposes public API. Do not modify public signature or break backwards compatibility.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (public-api): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Public API Surface Protection", "- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.");
    }

    private void appendStrictExceptions(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                         StringBuilder codexSec, StringBuilder copilotSec,
                                         StringBuilder qwenSec, StringBuilder geminiSec,
                                         StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Strict exception handling required. Catching/throwing generic Exception/Throwable is prohibited.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <exceptions>strict</exceptions>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtStrictExceptions.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtStrictExceptions.append("### ").append(className).append("\n- Enforce precise exception handling. Prohibit catching or throwing generic Exceptions/Throwables. Use specific or custom exceptions.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### STRICT EXCEPTIONS: ").append(className).append("\n- **Rule**: Prohibit catching or throwing generic Exception/Throwable. Use custom, domain-specific exceptions.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (strict-exceptions): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Strict Exception Handling", "- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions.");
    }

    private void appendStrictTypes(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                   StringBuilder codexSec, StringBuilder copilotSec,
                                   StringBuilder qwenSec, StringBuilder geminiSec,
                                   StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Loose typing (Object, Map<String, Object>, raw types) is prohibited. Enforce type safety.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <types>strict</types>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtStrictTypes.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtStrictTypes.append("### ").append(className).append("\n- Prohibit loose typing. Use strongly-typed transfer objects or domain models instead of Object or Map<String, Object>.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### STRICT TYPES: ").append(className).append("\n- **Rule**: Loose typing is prohibited. Enforce explicit type-safety and strongly-typed objects.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (strict-types): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Strict Type Safety", "- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.");
    }

    private void appendInternationalized(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                         StringBuilder codexSec, StringBuilder copilotSec,
                                         StringBuilder qwenSec, StringBuilder geminiSec,
                                         StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Internationalization mandated. User-facing strings must not be hardcoded; retrieve from resources.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <i18n>required</i18n>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtInternationalized.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtInternationalized.append("### ").append(className).append("\n- Internationalization mandate. Prohibit hardcoding user-facing strings; all user-visible text must be localized.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### INTERNATIONALIZED: ").append(className).append("\n- **Rule**: Internationalization required. Do not hardcode user-facing labels or strings.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (i18n): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Internationalization Mandate", "- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources.");
    }

    private void appendStrictClasspath(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                       StringBuilder codexSec, StringBuilder copilotSec,
                                       StringBuilder qwenSec, StringBuilder geminiSec,
                                       StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Strict compile-time dependency/classpath constraints. Dynamic loading and reflection hacks prohibited.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <classpath>strict</classpath>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtStrictClasspath.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtStrictClasspath.append("### ").append(className).append("\n- Strict classpath integrity. Prohibit dynamic runtime class loading, reflections, or external JAR injection.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### STRICT CLASSPATH: ").append(className).append("\n- **Rule**: Enforce strict classpath integrity. Dynamic loading or custom classloaders are prohibited.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (strict-classpath): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Strict Classpath Integrity", "- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.");
    }

    private void appendSchemaSafe(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                  StringBuilder codexSec, StringBuilder copilotSec,
                                  StringBuilder qwenSec, StringBuilder geminiSec,
                                  StringBuilder windsurfSec, StringBuilder zedSec) {
        String className = ElementNaming.elementPath(e);
        String summary = "Schema/serialization safety guaranteed. Prohibit altering data formats or fields without migration plan.";
        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <schema>safe</schema>\n    </element>\n");
        }
        if (llmsActive)     llmsTxtSchemaSafe.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) llmsFullTxtSchemaSafe.append("### ").append(className).append("\n- Schema and serialization safety. Restrict changing serialization formats, database fields, or API models without a migration path.\n\n");
        if (aiderConvActive)   aiderConventions.append("#### SCHEMA SAFE: ").append(className).append("\n- **Rule**: Schema safety required. Do not change serialization formats, database columns, or API models without a migration plan.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (schema-safe): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Schema & Serialization Safety", "- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.");
    }

    private void appendSecure(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                              StringBuilder codexSec, StringBuilder copilotSec,
                              StringBuilder qwenSec, StringBuilder geminiSec,
                              StringBuilder windsurfSec, StringBuilder zedSec) {
        AISecure secure = e.getAnnotation(AISecure.class);
        if (secure == null) return;
        String className = ElementNaming.elementPath(e);
        String aspect = secure.aspect();
        String summary = "Security-critical code" + (aspect.isEmpty() ? "" : " [" + aspect + "]")
                       + ". Do not weaken security properties. Flag any change for security review.";

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n");
            if (!aspect.isEmpty()) claudeSec.append("      <aspect>").append(aspect).append("</aspect>\n");
            claudeSec.append("    </element>\n");
        }
        if (llmsActive)     llmsTxtSecure.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtSecure.append("### ").append(className).append("\n- Security-critical code");
            if (!aspect.isEmpty()) llmsFullTxtSecure.append(" (aspect: ").append(aspect).append(")");
            llmsFullTxtSecure.append(".\n- Never weaken security properties. Every change requires explicit security review.\n\n");
        }
        if (aiderConvActive)   aiderConventions.append("#### SECURITY-CRITICAL: ").append(className).append("\n").append(aspect.isEmpty() ? "" : "- **Aspect**: " + aspect + "\n").append("- **Rule**: Do not weaken security properties. Every change must be reviewed for security impact.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (sweepActive)       sweepRules.append("  - \"Security-critical: ").append(className).append(" [").append(aspect.isEmpty() ? "general" : aspect).append("]. Do not weaken security.\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (security-critical): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Security-Critical Code", "- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact." + (aspect.isEmpty() ? "" : "\n- **Aspect**: " + aspect));
    }

    private void appendFeatureFlag(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                   StringBuilder codexSec, StringBuilder copilotSec,
                                   StringBuilder qwenSec, StringBuilder geminiSec,
                                   StringBuilder windsurfSec, StringBuilder zedSec) {
        AIFeatureFlag ff = e.getAnnotation(AIFeatureFlag.class);
        if (ff == null) return;
        String className = ElementNaming.elementPath(e);
        String flag = ff.flag();
        boolean defaultValue = ff.defaultValue();
        String flagDisplay = flag.isEmpty() ? "(unspecified)" : "'" + flag + "'";
        String summary = "Gated by feature flag: " + flagDisplay + " (default: " + defaultValue + "). "
                       + "Preserve the flag check — never assume it is always on.";

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n");
            if (!flag.isEmpty()) claudeSec.append("      <flag>").append(flag).append("</flag>\n");
            claudeSec.append("      <default_value>").append(defaultValue).append("</default_value>\n");
            claudeSec.append("    </element>\n");
        }
        if (llmsActive)     llmsTxtFeatureFlag.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtFeatureFlag.append("### ").append(className).append("\n- Feature flag: ").append(flagDisplay).append(" (default: ").append(defaultValue).append(")\n");
            llmsFullTxtFeatureFlag.append("- Preserve the flag check. Never assume the flag is always active. Test both enabled and disabled code paths.\n\n");
        }
        if (aiderConvActive)   aiderConventions.append("#### FEATURE FLAG: ").append(className).append("\n- **Flag**: ").append(flagDisplay).append(" (default: ").append(defaultValue).append(")\n- **Rule**: Never assume flag is always active. Preserve the flag check.\n\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (sweepActive)       sweepRules.append("  - \"Feature flag gate for ").append(className).append(": ").append(summary).append("\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (feature-flag): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Feature Flag Gate", "- **Flag**: " + flagDisplay + " (default: " + defaultValue + ")\n- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.");
    }

    private void appendIdempotent(Element e, StringBuilder cursorSec, StringBuilder claudeSec,
                                  StringBuilder codexSec, StringBuilder copilotSec,
                                  StringBuilder qwenSec, StringBuilder geminiSec,
                                  StringBuilder windsurfSec, StringBuilder zedSec) {
        AIIdempotent idempotent = e.getAnnotation(AIIdempotent.class);
        if (idempotent == null) return;
        String className = ElementNaming.elementPath(e);
        String reason = idempotent.reason();
        String summary = "Idempotency guaranteed. Multiple invocations must produce the same result as one."
                       + (reason.isEmpty() ? "" : " Reason: " + reason);

        appendCommonRow(cursorSec, codexSec, copilotSec, qwenSec, geminiSec, className, summary);
        if (claudeActive) {
            claudeSec.append("    <element path=\"").append(className).append("\">\n      <idempotent>true</idempotent>\n");
            if (!reason.isEmpty()) claudeSec.append("      <reason>").append(reason).append("</reason>\n");
            claudeSec.append("    </element>\n");
        }
        if (llmsActive)     llmsTxtIdempotent.append("- [").append(ElementNaming.elementDisplayName(e)).append("](").append(className).append("): ").append(summary).append("\n");
        if (llmsFullActive) {
            llmsFullTxtIdempotent.append("### ").append(className).append("\n- Idempotency guaranteed. Multiple invocations must produce the same result as a single invocation.\n");
            if (!reason.isEmpty()) llmsFullTxtIdempotent.append("- **Reason**: ").append(reason).append("\n");
            llmsFullTxtIdempotent.append("\n");
        }
        if (aiderConvActive)   aiderConventions.append("#### IDEMPOTENT: ").append(className).append("\n- **Rule**: Must remain idempotent. Multiple invocations must produce the same result as one.\n").append(reason.isEmpty() ? "" : "- **Reason**: " + reason + "\n").append("\n");
        if (windsurfActive)    windsurfSec.append("* `").append(className).append("` - ").append(summary).append("\n");
        if (zedActive)         zedSec.append("- `").append(className).append("`: ").append(summary).append("\n");
        if (sweepActive)       sweepRules.append("  - \"Idempotency requirement for ").append(className).append(": ").append(summary).append("\"\n");
        if (interpreterActive) interpreterRules.append("- `").append(className).append("` (idempotent): ").append(summary).append("\n");
        if (granularActive)    appendToGranular(e, "Idempotency Guarantee", "- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once." + (reason.isEmpty() ? "" : "\n- **Reason**: " + reason));
    }

    /** Converts a String[] to a JSON array of quoted strings: "\"a\", \"b\"". */
    private static String buildJsonStringArray(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("\"").append(v).append("\"");
        }
        return sb.toString();
    }
}
