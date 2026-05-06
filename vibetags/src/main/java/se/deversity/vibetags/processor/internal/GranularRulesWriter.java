package se.deversity.vibetags.processor.internal;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Writes per-class granular rule files for Cursor (.mdc), Trae (.md), and Roo (.md).
 * Each annotated class/package becomes one file in the platform's rules directory.
 *
 * <p>Returns the set of qNames written so the caller can pass them to
 * {@link GuardrailFileWriter#cleanupGranularDirectory(Path, String, Set)} as the exclude list,
 * preventing a delete-then-recreate cycle on each compile.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class GranularRulesWriter {

    private final GuardrailFileWriter fileWriter;

    public GranularRulesWriter(GuardrailFileWriter fileWriter) {
        this.fileWriter = fileWriter;
    }

    /**
     * @param elementRules    map of owning class/package element → accumulated rules markdown
     * @param serviceFiles    service-key → directory path map
     * @param activeServices  currently-active services (controls which platforms get files)
     * @return qNames (filename minus extension) of files just written
     */
    public Set<String> writeAll(Map<Element, StringBuilder> elementRules,
                                Map<String, Path> serviceFiles,
                                Set<String> activeServices) {
        Set<String> writtenQNames = new LinkedHashSet<>();
        boolean cursorGranular    = activeServices.contains("cursor_granular");
        boolean traeGranular      = activeServices.contains("trae_granular");
        boolean rooGranular       = activeServices.contains("roo_granular");
        boolean windsurfGranular  = activeServices.contains("windsurf_granular");
        boolean continueGranular  = activeServices.contains("continue_granular");
        boolean tabnineGranular   = activeServices.contains("tabnine_granular");
        boolean amazonqGranular   = activeServices.contains("amazonq_granular");
        boolean aiRulesGranular   = activeServices.contains("ai_rules_granular");
        if (!cursorGranular && !traeGranular && !rooGranular
                && !windsurfGranular && !continueGranular
                && !tabnineGranular && !amazonqGranular && !aiRulesGranular) return writtenQNames;

        elementRules.forEach((element, builder) -> {
            String qName = element.toString().replace('.', '-').replaceAll("[^a-zA-Z0-9-]", "-");
            writtenQNames.add(qName);
            String simpleName = element.getSimpleName().toString();
            String rulesContent = builder.toString().trim();
            String glob = ElementKind.PACKAGE.equals(element.getKind())
                ? "**/" + simpleName + "/**/*.java"
                : "**/" + simpleName + ".java";

            if (cursorGranular) {
                String mdc = "---\n"
                    + "description: \"AI rules for " + element + "\"\n"
                    + "globs: [\"" + glob + "\"]\n"
                    + "alwaysApply: false\n"
                    + "---\n\n"
                    + "# Rules for " + simpleName + "\n\n"
                    + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("cursor_granular").resolve(qName + ".mdc").toString(), mdc, true);
            }
            if (traeGranular) {
                String md = "---\n"
                    + "alwaysApply: false\n"
                    + "globs: [\"" + glob + "\"]\n"
                    + "description: \"AI rules for " + element + "\"\n"
                    + "---\n\n"
                    + "# Rules for " + simpleName + "\n\n"
                    + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("trae_granular").resolve(qName + ".md").toString(), md, true);
            }
            if (rooGranular) {
                String md = "# Rules for " + simpleName + "\n\n" + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("roo_granular").resolve(qName + ".md").toString(), md, true);
            }
            if (windsurfGranular) {
                String md = "---\n"
                    + "description: \"AI rules for " + element + "\"\n"
                    + "globs: [\"" + glob + "\"]\n"
                    + "alwaysApply: false\n"
                    + "---\n\n"
                    + "# Rules for " + simpleName + "\n\n"
                    + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("windsurf_granular").resolve(qName + ".md").toString(), md, true);
            }
            if (continueGranular) {
                String md = "---\n"
                    + "description: \"AI rules for " + element + "\"\n"
                    + "globs: [\"" + glob + "\"]\n"
                    + "alwaysApply: false\n"
                    + "---\n\n"
                    + "# Rules for " + simpleName + "\n\n"
                    + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("continue_granular").resolve(qName + ".md").toString(), md, true);
            }
            if (tabnineGranular) {
                String md = "# AI Guidelines for " + simpleName + "\n\n" + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("tabnine_granular").resolve(qName + ".md").toString(), md, true);
            }
            if (amazonqGranular) {
                String md = "# Amazon Q Rules for " + simpleName + "\n\n" + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("amazonq_granular").resolve(qName + ".md").toString(), md, true);
            }
            if (aiRulesGranular) {
                String md = "# Rules for " + simpleName + "\n\n" + rulesContent;
                fileWriter.writeFileIfChanged(
                    serviceFiles.get("ai_rules_granular").resolve(qName + ".md").toString(), md, true);
            }
        });
        return writtenQNames;
    }

    /**
     * Removes orphaned granular files for the three platforms, skipping {@code excludeQNames}
     * (typically the names just written this round).
     */
    public void cleanupAll(Map<String, Path> serviceFiles, Set<String> activeServices, Set<String> excludeQNames) {
        if (activeServices.contains("cursor_granular"))   fileWriter.cleanupGranularDirectory(serviceFiles.get("cursor_granular"),   ".mdc", excludeQNames);
        if (activeServices.contains("trae_granular"))     fileWriter.cleanupGranularDirectory(serviceFiles.get("trae_granular"),     ".md",  excludeQNames);
        if (activeServices.contains("roo_granular"))      fileWriter.cleanupGranularDirectory(serviceFiles.get("roo_granular"),      ".md",  excludeQNames);
        if (activeServices.contains("windsurf_granular")) fileWriter.cleanupGranularDirectory(serviceFiles.get("windsurf_granular"), ".md",  excludeQNames);
        if (activeServices.contains("continue_granular")) fileWriter.cleanupGranularDirectory(serviceFiles.get("continue_granular"), ".md",  excludeQNames);
        if (activeServices.contains("tabnine_granular"))  fileWriter.cleanupGranularDirectory(serviceFiles.get("tabnine_granular"),  ".md",  excludeQNames);
        if (activeServices.contains("amazonq_granular"))  fileWriter.cleanupGranularDirectory(serviceFiles.get("amazonq_granular"),  ".md",  excludeQNames);
        if (activeServices.contains("ai_rules_granular")) fileWriter.cleanupGranularDirectory(serviceFiles.get("ai_rules_granular"), ".md",  excludeQNames);
    }
}
