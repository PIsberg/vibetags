import sys
import re

file_path = r'c:\dev\private\vibetags\vibetags\src\main\java\se\deversity\vibetags\processor\AIGuardrailProcessor.java'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Define the new process() and generateFiles() methods
new_methods = """
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            if (!processed) {
                generateFiles();
                processed = true;
            }
            return false;
        }

        // Aggregate elements from the current round
        lockedElements.addAll(roundEnv.getElementsAnnotatedWith(AILocked.class));
        contextElements.addAll(roundEnv.getElementsAnnotatedWith(AIContext.class));
        ignoreElements.addAll(roundEnv.getElementsAnnotatedWith(AIIgnore.class));
        auditElements.addAll(roundEnv.getElementsAnnotatedWith(AIAudit.class));
        draftElements.addAll(roundEnv.getElementsAnnotatedWith(AIDraft.class));
        privacyElements.addAll(roundEnv.getElementsAnnotatedWith(AIPrivacy.class));

        // Validation in each round for early feedback
        validateAnnotations(processingEnv.getMessager(), roundEnv);

        return false; // allow others to see the annotations
    }

    private void generateFiles() {
        if (log != null) {
            log.info("VibeTags v{} | Finalizing Output Files...", VERSION);
        }
        
        // Fetch project metadata
        String rootOverride = processingEnv.getOptions().get("vibetags.root");
        String rootPath = (rootOverride != null && !rootOverride.isBlank())
                ? rootOverride
                : Paths.get("").toAbsolutePath().toString();
        this.root = Paths.get(rootPath);
        this.projectName = processingEnv.getOptions().getOrDefault("vibetags.project", "This Project");

        if (log != null) {
            log.info("Project: {}", projectName);
            log.info("Root:    {}", rootPath);
        }

        // Initialization of all StringBuilders
        initBuilders();

        // Process @AILocked
        for (Element element : lockedElements) {
            AILocked locked = element.getAnnotation(AILocked.class);
            String className = element.toString();
            String reason = locked.reason();

            cursorRules.append("* `").append(className).append("` - Reason: ").append(reason).append("\\n");
            claudeMd.append("    <file path=\\"").append(className).append("\\">\\n")
                    .append("      <reason>").append(reason).append("</reason>\\n")
                    .append("    </file>\\n");
            aiExclude.append("**/").append(element.getSimpleName()).append(".java\\n");
            codexAgents.append("- **").append(className).append("**: ").append(reason).append("\\n");
            copilot.append("- `").append(className).append("` - ").append(reason).append("\\n");
            qwenMd.append("* `").append(className).append("` - ").append(reason).append("\\n");

            llmsTxt.append("- [").append(element.getSimpleName()).append("](").append(className).append("): ").append(reason).append("\\n");
            llmsFullTxt.append("### ").append(className).append("\\n")
                       .append("- **Reason**: ").append(reason).append("\\n\\n");
        }

        claudeMd.append("  </locked_files>\\n  <contextual_instructions>\\n");
        cursorRules.append("\\n## CONTEXTUAL RULES\\n");
        codexAgents.append("\\n## CONTEXTUAL RULES\\n");
        copilot.append("\\n## Contextual Guidelines\\n");
        qwenMd.append("\\n## CONTEXTUAL RULES\\n");

        // Process @AIContext
        for (Element element : contextElements) {
            AIContext context = element.getAnnotation(AIContext.class);
            String className = element.toString();

            cursorRules.append("* `").append(className).append("`\\n  * Focus: ").append(context.focus())
                       .append("\\n  * Avoid: ").append(context.avoids()).append("\\n");
            claudeMd.append("    <file path=\\"").append(className).append("\\">\\n")
                    .append("      <focus>").append(context.focus()).append("</focus>\\n")
                    .append("      <avoids>").append(context.avoids()).append("</avoids>\\n")
                    .append("    </file>\\n");
            codexAgents.append("- `").append(className).append("`: Focus on ").append(context.focus())
                       .append(". Avoid ").append(context.avoids()).append(".\\n");
            copilot.append("- `").append(className).append("`\\n")
                   .append("  - Focus: ").append(context.focus()).append("\\n")
                   .append("  - Avoid: ").append(context.avoids()).append("\\n");
            qwenMd.append("* `").append(className).append("`\\n")
                  .append("  * Focus: ").append(context.focus()).append("\\n")
                  .append("  * Avoid: ").append(context.avoids()).append("\\n");

            llmsTxtContext.append("- [").append(element.getSimpleName()).append("](").append(className)
                          .append("): Focus - ").append(context.focus())
                          .append(". Avoid - ").append(context.avoids()).append("\\n");
            llmsFullTxtContext.append("### ").append(className).append("\\n")
                              .append("- **Focus**: ").append(context.focus()).append("\\n")
                              .append("- **Avoid**: ").append(context.avoids()).append("\\n\\n");
        }
        claudeMd.append("  </contextual_instructions>\\n");

        // Builders for ignored elements
        StringBuilder cursorIgnore = new StringBuilder("\\n## \uD83D\uDEAB IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\\nDo not reference, suggest changes to, or include the following in completions or answers.\\n\\n");
        StringBuilder claudeIgnore = new StringBuilder("  <ignored_elements>\\n");
        StringBuilder codexIgnore = new StringBuilder("\\n## IGNORED ELEMENTS\\nThe following elements must be completely excluded from AI context and completions:\\n\\n");
        StringBuilder geminiIgnore = new StringBuilder("## IGNORED ELEMENTS\\nThe following elements must be completely excluded from AI context and completions:\\n\\n");
        StringBuilder copilotIgnore = new StringBuilder("\\n## Ignored Elements\\nDo not reference or suggest changes to the following:\\n\\n");
        StringBuilder qwenIgnore = new StringBuilder("\\n## IGNORED ELEMENTS\\nThe following elements must be completely excluded from AI\\'s memory and context:\\n\\n");

        // Process @AIIgnore
        for (Element element : ignoreElements) {
            String className = element.toString();
            cursorIgnore.append("* `").append(className).append("`\\n");
            claudeIgnore.append("    <file path=\\"").append(className).append("\\"/>\\n");
            aiExclude.append("**/").append(element.getSimpleName()).append(".java\\n");
            codexIgnore.append("- `").append(className).append("`\\n");
            geminiIgnore.append("- `").append(className).append("`\\n");
            copilotIgnore.append("- `").append(className).append("`\\n");

            String globPattern = "**/"+ element.getSimpleName() + ".java\\n";
            cursorIgnoreFile.append(globPattern);
            claudeIgnoreFile.append(globPattern);
            copilotIgnoreFile.append(globPattern);
            qwenIgnoreFile.append(globPattern);
            qwenIgnore.append("* `").append(className).append("`\\n");

            llmsTxtIgnore.append("- [").append(element.getSimpleName()).append("](").append(className)
                         .append("): excluded from AI context\\n");
            llmsFullTxtIgnore.append("### ").append(className).append("\\n")
                             .append("- Excluded from AI context entirely - treat as non-existent\\n\\n");
        }

        // Builders for audit sections
        StringBuilder cursorAudit = new StringBuilder("\\n## 🛡️ MANDATORY SECURITY AUDITS\\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\\n\\n");
        StringBuilder claudeAudit = new StringBuilder("\\n  <audit_requirements>\\n");
        StringBuilder geminiMd = new StringBuilder("# GEMINI AI INSTRUCTIONS\\n" + GENERATED_HEADER + "\\n## CONTINUOUS AUDIT REQUIREMENTS\\n" +
            "You are acting as a Senior Staff Engineer. Whenever you write code for the files listed below, you must ensure your completions and chat responses strictly prevent the listed vulnerabilities:\\n\\n");
        StringBuilder codexAudit = new StringBuilder("\\n## 🛡️ MANDATORY SECURITY AUDITS\\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\\n\\n");
        StringBuilder copilotAudit = new StringBuilder("\\n## Security Audit Requirements\\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\\n\\n");
        StringBuilder qwenAudit = new StringBuilder("\\n## 🛡️ MANDATORY SECURITY AUDITS\\nWhen proposing edits or writing code for the following files, you MUST perform a security review. Explicitly state that you have audited the changes for the listed vulnerabilities.\\n\\n");

        // Process @AIAudit
        for (Element element : auditElements) {
            AIAudit audit = element.getAnnotation(AIAudit.class);
            String className = element.toString();
            String[] checkFor = audit.checkFor();
            if (checkFor.length == 0) continue;

            cursorAudit.append("* `").append(className).append("`\\n");
            cursorAudit.append("  - Required Checks: ").append(String.join(", ", checkFor)).append("\\n");
            claudeAudit.append("    <file path=\\"").append(className).append("\\">\\n");
            for (String vulnerability : checkFor) {
                claudeAudit.append("      <vulnerability_check>").append(vulnerability).append("</vulnerability_check>\\n");
            }
            claudeAudit.append("    </file>\\n");
            geminiMd.append("File: `").append(className).append("`")
                    .append("\\nCritical Vulnerabilities to Prevent: ");
            for (String vulnerability : checkFor) {
                geminiMd.append("\\n- ").append(vulnerability);
            }
            geminiMd.append("\\n\\n");
            codexAudit.append("* `").append(className).append("`\\n")
                      .append("  - Required Checks: ").append(String.join(", ", checkFor)).append("\\n");
            copilotAudit.append("- `").append(className).append("`\\n")
                        .append("  - Required Checks: ").append(String.join(", ", checkFor)).append("\\n");
            qwenAudit.append("* `").append(className).append("`\\n")
                     .append("  - Required Checks: ").append(String.join(", ", checkFor)).append("\\n");

            llmsTxtAudit.append("- [").append(element.getSimpleName()).append("](").append(className)
                        .append("): check for ").append(String.join(", ", checkFor)).append("\\n");
            llmsFullTxtAudit.append("### ").append(className).append("\\n")
                            .append("- **Required Checks**: ").append(String.join(", ", checkFor)).append("\\n\\n");
        }

        // Builders for draft elements
        StringBuilder cursorDraft = new StringBuilder("\\n## 📝 IMPLEMENTATION TASKS (TODO)\\n" +
            "The following elements are currently in DRAFT mode. Follow the instructions to implement them:\\n\\n");
        StringBuilder claudeDraft = new StringBuilder("  <implementation_tasks>\\n");
        StringBuilder codexDraft = new StringBuilder("\\n## IMPLEMENTATION TASKS\\nThe following elements are drafts that need implementation:\\n\\n");
        StringBuilder geminiDraft = new StringBuilder("## IMPLEMENTATION TASKS\\nThe following elements are drafts that need implementation:\\n\\n");
        StringBuilder copilotDraft = new StringBuilder("\\n## Implementation Tasks\\nFollow these instructions to implement the drafts:\\n\\n");

        // Process @AIDraft
        for (Element element : draftElements) {
            AIDraft draft = element.getAnnotation(AIDraft.class);
            String className = element.toString();
            String instructions = draft.instructions();
            
            cursorDraft.append("* `").append(className).append("` - Task: ").append(instructions).append("\\n");
            claudeDraft.append("    <task path=\\"").append(className).append("\\">\\n")
                       .append("      <instructions>").append(instructions).append("</instructions>\\n")
                       .append("    </task>\\n");
            codexDraft.append("- **").append(className).append("**: ").append(instructions).append("\\n");
            geminiDraft.append("- `").append(className).append("`: ").append(instructions).append("\\n");
            copilotDraft.append("- `").append(className).append("`: ").append(instructions).append("\\n");

            llmsTxtDraft.append("- [").append(element.getSimpleName()).append("](").append(className)
                        .append("): ").append(instructions).append("\\n");
            llmsFullTxtDraft.append("### ").append(className).append("\\n")
                            .append("- **Instructions**: ").append(instructions).append("\\n\\n");
        }

        // Builders for privacy elements
        StringBuilder cursorPrivacy = new StringBuilder("\\n## \uD83D\uDD12 PII / PRIVACY GUARDRAILS\\n" +
            "The following elements handle Personally Identifiable Information (PII).\\n" +
            "NEVER include their runtime values in logs, console output, external API calls,\\n" +
            "test fixtures, mock data, or code suggestions.\\n\\n");
        StringBuilder claudePrivacy = new StringBuilder("  <pii_guardrails>\\n");
        StringBuilder codexPrivacy = new StringBuilder("\\n## \uD83D\uDD12 PII / PRIVACY GUARDRAILS\\n" +
            "The following elements handle PII. Never include their runtime values in logs,\\n" +
            "console output, external API calls, test fixtures, or mock data.\\n\\n");
        StringBuilder geminiPrivacy = new StringBuilder("## PII / PRIVACY GUARDRAILS\\n" +
            "The following elements handle Personally Identifiable Information (PII).\\n" +
            "Never include their runtime values in logs, console output, external API calls,\\n" +
            "test fixtures, mock data, or code suggestions.\\n\\n");
        StringBuilder copilotPrivacy = new StringBuilder("\\n## PII / Privacy Guardrails\\n" +
            "Never log, expose, or suggest code that outputs the runtime values of these elements:\\n\\n");
        StringBuilder qwenPrivacy = new StringBuilder("\\n## \uD83D\uDD12 PII / PRIVACY GUARDRAILS\\n" +
            "The following elements handle PII. Never include their runtime values in logs,\\n" +
            "console output, external API calls, test fixtures, or mock data.\\n\\n");

        // Process @AIPrivacy
        for (Element element : privacyElements) {
            AIPrivacy privacy = element.getAnnotation(AIPrivacy.class);
            String className = element.toString();
            String reason = privacy.reason();

            cursorPrivacy.append("* `").append(className).append("` - ").append(reason).append("\\n");
            claudePrivacy.append("    <element path=\\"").append(className).append("\\">\\n")
                         .append("      <reason>").append(reason).append("</reason>\\n")
                         .append("    </element>\\n");
            codexPrivacy.append("- `").append(className).append("`: ").append(reason).append("\\n");
            geminiPrivacy.append("- `").append(className).append("`: ").append(reason).append("\\n");
            copilotPrivacy.append("- `").append(className).append("` - ").append(reason).append("\\n");
            qwenPrivacy.append("* `").append(className).append("` - ").append(reason).append("\\n");

            llmsTxtPrivacy.append("- [").append(element.getSimpleName()).append("](").append(className)
                          .append("): ").append(reason).append("\\n");
            llmsFullTxtPrivacy.append("### ").append(className).append("\\n")
                              .append("- **Reason**: ").append(reason).append("\\n\\n");
        }

        // Finalize sections
        if (!auditElements.isEmpty()) {
            cursorRules.append(cursorAudit);
            claudeAudit.append("  </audit_requirements>\\n");
            claudeMd.append(claudeAudit);
            claudeMd.append("\\n<rule>\\n  If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed <vulnerability_check> items. If your code introduces these vulnerabilities, you must rewrite it before displaying it to the user.\\n</rule>\\n");
            codexAgents.append(codexAudit);
            copilot.append(copilotAudit);
            qwenMd.append(qwenAudit);
        }

        if (!ignoreElements.isEmpty()) {
            cursorRules.append(cursorIgnore);
            claudeIgnore.append("  </ignored_elements>\\n");
            claudeMd.append(claudeIgnore);
            claudeMd.append("\\n<rule>Never reference or suggest changes to any element listed in <ignored_elements>. Treat these as if they do not exist.</rule>\\n");
            codexAgents.append(codexIgnore);
            geminiMd.append(geminiIgnore);
            copilot.append(copilotIgnore);
            qwenMd.append(qwenIgnore);
        }

        if (!draftElements.isEmpty()) {
            cursorRules.append(cursorDraft);
            claudeDraft.append("  </implementation_tasks>\\n");
            claudeMd.append(claudeDraft);
            codexAgents.append(codexDraft);
            geminiMd.append(geminiDraft);
            copilot.append(copilotDraft);
        }

        if (!privacyElements.isEmpty()) {
            cursorRules.append(cursorPrivacy);
            claudePrivacy.append("  </pii_guardrails>\\n");
            claudeMd.append(claudePrivacy);
            claudeMd.append("\\n<rule>\\n  Never include runtime values of elements listed in <pii_guardrails> in logs, console output, external API calls, test fixtures, mock data, or code suggestions. Treat their values as strictly confidential.\\n</rule>\\n");
            codexAgents.append(codexPrivacy);
            geminiMd.append(geminiPrivacy);
            copilot.append(copilotPrivacy);
            qwenMd.append(qwenPrivacy);
        }

        claudeMd.append("</project_guardrails>\\n");
        claudeMd.append("\\n<rule>Never propose edits to files listed in <locked_files>.</rule>\\n");

        // Assemble llms.txt and llms-full.txt
        llmsTxt.append(llmsTxtContext);
        llmsFullTxt.append(llmsFullTxtContext);
        if (!auditElements.isEmpty()) {
            llmsTxt.append(llmsTxtAudit);
            llmsFullTxt.append(llmsFullTxtAudit);
        }
        if (!ignoreElements.isEmpty()) {
            llmsTxt.append(llmsTxtIgnore);
            llmsFullTxt.append(llmsFullTxtIgnore);
        }
        if (!draftElements.isEmpty()) {
            llmsTxt.append(llmsTxtDraft);
            llmsFullTxt.append(llmsFullTxtDraft);
        }
        if (!privacyElements.isEmpty()) {
            llmsTxt.append(llmsTxtPrivacy);
            llmsFullTxt.append(llmsFullTxtPrivacy);
        }

        // Active services resolution
        Map<String, Path> serviceFiles = buildServiceFileMap(this.root);
        Set<String> activeServices = resolveActiveServices(processingEnv.getMessager(), serviceFiles);

        if (log != null) {
            log.info("Active services ({}): {}", activeServices.size(),
                activeServices.stream().sorted().collect(Collectors.joining(", ")));
        }

        Map<String, String> contentByService = new java.util.LinkedHashMap<>();
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
            contentByService.put("codex_config", "# " + GENERATED_HEADER.trim() + "\\n[project]\\nmodel = \\"o3-mini\\"\\napproval_policy = \\"on-request\\"\\n");
            contentByService.put("codex_rules", 
                "# " + GENERATED_HEADER.trim() + "\\n# VibeTags: Starlark Command Permissions\\n\\n" +
                "prefix_rule(\\"ls\\", \\"allow\\")\\n" +
                "prefix_rule(\\"cat\\", \\"allow\\")\\n" +
                "prefix_rule(\\"grep\\", \\"allow\\")\\n" +
                "prefix_rule(\\"mvn\\", \\"prompt\\")\\n" +
                "prefix_rule(\\"npm\\", \\"prompt\\")\\n" +
                "prefix_rule(\\"git\\", \\"prompt\\")\\n" +
                "prefix_rule(\\"rm\\", \\"prompt\\")\\n");
        }
        if (activeServices.contains("qwen")) {
            contentByService.put("qwen_settings", "{\\n  \\"project\\": {\\n    \\"model\\": \\"qwen3-coder-plus\\",\\n    \\"mcp\\": {\\n      \\"enabled\\": true\\n    }\\n  }\\n}\\n");
            contentByService.put("qwen_refactor", "# /refactor command\\n# AUTO-GENERATED BY VIBETAGS\\n\\nRefactor the current selection to improve maintainability and performance while strictly following the project\\'s contextual rules in QWEN.md.\\n");
        }

        Messager messager = processingEnv.getMessager();
        contentByService.forEach((service, content) -> {
            Path filePath = serviceFiles.get(service);
            boolean changed = writeFileIfChanged(filePath.toString(), content);
            String relPath = this.root.relativize(filePath).toString().replace('\\\\', '/');
            String status = changed ? "updated" : "no changes";
            messager.printMessage(Diagnostic.Kind.NOTE, "VibeTags: " + status + " - " + relPath);
            if (log != null) {
                if (changed) log.info("{} - updated", relPath);
                else         log.info("{} - no changes", relPath);
            }
        });
        
        checkOrphanedAnnotations(messager, activeServices, 
            !lockedElements.isEmpty(),
            !ignoreElements.isEmpty(), 
            !auditElements.isEmpty());
    }
"""

# Extract the block to replace: from '@Override' at line 163 to line 642
# (Line numbers are 1-based, we use 0-based for slicing but since we use regex it's safer)
pattern = re.compile(r'\s+@Override\s+public\s+boolean\s+process.*?return\s+false;\s+//\s+do\s+not\s+claim\s+annotations\s+--\s+other\s+processors\s+must\s+still\s+see\s+them\s+}', re.DOTALL)

# Replace the block
modified_content = re.sub(pattern, new_methods, content)

# Check if replacement occurred
if modified_content == content:
    print("Error: Block not found or replacement failed.")
    # Fallback to line-based replacement if regex fails
    lines = content.splitlines()
    # Lines 163 to 642 (1-indexed)
    del lines[162:642]
    lines.insert(162, new_methods)
    modified_content = "\\n".join(lines)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(modified_content)

print("Replacement successful.")
