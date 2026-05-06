package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AITestDriven;

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

    // Windsurf test-driven section
    private StringBuilder windsurfTestDrivenSection;

    // Zed test-driven section
    private StringBuilder zedTestDrivenSection;

    // llms.txt test-driven sections
    private StringBuilder llmsTxtTestDriven;
    private StringBuilder llmsFullTxtTestDriven;

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
                                    || activeServices.contains("pearai_granular");
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
              + collector.testDriven().size();
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
        }

        return new Result(buildContentMap(geminiMd), elementRules);
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
        if (geminiActive)  geminiLocked.append("- `").append(className).append("`: ").append(reason).append("\n");

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
        if (geminiActive)    geminiContext.append("- `").append(className).append("`: Focus - ").append(context.focus()).append(". Avoid - ").append(context.avoids()).append("\n");
    }

    private void appendIgnore(Element e, StringBuilder claudeIgnoreSec, StringBuilder codexIgnoreSec,
                              StringBuilder geminiIgnoreSec, StringBuilder copilotIgnoreSec,
                              StringBuilder qwenIgnoreSec) {
        String className = ElementNaming.elementPath(e);
        if (cursorActive)    cursorIgnoreSection.append("* `").append(className).append("` \n");
        if (claudeActive)    claudeIgnoreSec.append("    <file path=\"").append(className).append("\"/>\n");
        if (aiexcludeActive) aiExclude.append("**/").append(e.getSimpleName()).append(".java\n");
        if (codexActive)     codexIgnoreSec.append("- `").append(className).append("` \n");
        if (geminiActive)    geminiIgnoreSec.append("- `").append(className).append("` \n");
        if (copilotActive)   copilotIgnoreSec.append("- `").append(className).append("` \n");

        String globPattern = "**/" + e.getSimpleName() + ".java\n";
        if (cursorIgnoreFileActive)   cursorIgnoreFile.append(globPattern);
        if (claudeIgnoreFileActive)   claudeIgnoreFile.append(globPattern);
        if (copilotIgnoreFileActive)  copilotIgnoreFile.append(globPattern);
        if (qwenIgnoreFileActive)     qwenIgnoreFile.append(globPattern);
        if (codyIgnoreActive)       codyIgnoreFile.append(globPattern);
        if (supermavenIgnoreActive) supermavenIgnoreFile.append(globPattern);
        if (doubleIgnoreActive)     doubleIgnoreFile.append(globPattern);
        if (codeiumIgnoreActive)    codeiumIgnoreFile.append(globPattern);
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
        if (geminiActive) {
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
        if (geminiActive)  geminiDraftSec.append("- `").append(className).append("`: ").append(instructions).append("\n");
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
        if (geminiActive)  geminiPrivacySec.append("- `").append(className).append("`: ").append(reason).append("\n");
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
        if (geminiActive)  geminiCoreSec.append("- `").append(className).append("`: Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");

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
        if (geminiActive)  geminiPerfSec.append("- `").append(className).append("`: ").append(constraint).append("\n");

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
        if (geminiActive)  geminiContractSec.append("- `").append(className).append("`: ").append(reason).append("\n");

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
        if (geminiActive)  geminiTestDrivenSec.append("- `").append(className).append("`: ").append(summary).append("\n");

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

    private Map<String, String> buildContentMap(StringBuilder geminiMd) {
        Map<String, String> contentByService = new LinkedHashMap<>();
        if (activeServices.contains("cursor"))    contentByService.put("cursor",    cursorRules.toString());
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

        if (activeServices.contains("double_ignore"))   contentByService.put("double_ignore",   doubleIgnoreFile.toString());
        if (activeServices.contains("codeium_ignore"))  contentByService.put("codeium_ignore",  codeiumIgnoreFile.toString());
        if (activeServices.contains("mentat"))          contentByService.put("mentat",          buildMentatConfig());
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
