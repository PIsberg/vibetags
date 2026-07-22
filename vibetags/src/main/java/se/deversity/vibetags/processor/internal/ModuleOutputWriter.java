package se.deversity.vibetags.processor.internal;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/**
 * Writes a single module's own guardrail files into that module's own directory (nested,
 * directory-scoped output), so a reactor build can produce {@code module-a/CLAUDE.md} alongside the
 * merged reactor-root {@code CLAUDE.md}.
 *
 * <p>This is deliberately <em>not</em> the multi-module sidecar path: it re-runs the ordinary
 * single-module pipeline ({@link ServiceRegistry} → {@link GuardrailContentBuilder} →
 * {@link GuardrailFileWriter} / {@link GranularRulesWriter}) against the module's own directory,
 * using only this compilation's annotations and the module directory's own file-existence opt-ins.
 * There is no sidecar and no cross-module merge — a module's file contains exactly that module's
 * guardrails. The reactor-root files and the sidecar aggregation are untouched and orthogonal.
 *
 * <p>Because the content is built with the <em>module's</em> active-services set, the scoped-rules
 * index composes naturally: a module that opts into both its aggregate file and its granular
 * directory gets an indexed aggregate, exactly like the root.
 */
public final class ModuleOutputWriter {

    private ModuleOutputWriter() {}

    /**
     * Writes {@code collector}'s guardrails to the opted-in files under {@code moduleRoot}.
     *
     * @param moduleRoot     the compiling module's own directory (from {@code compilationRoot()})
     * @param vibetagsRoot   the VibeTags output root; when it equals {@code moduleRoot} this is a
     *                       no-op (the module <em>is</em> the root — root output already covers it)
     * @param moduleFiles    service-key → path map rooted at {@code moduleRoot}
     * @param moduleActive   services opted-in within {@code moduleRoot} (resolved quietly by caller)
     * @param collector      this compilation's annotated elements (module-scoped)
     * @param writer         the shared marker-aware, cache-backed file writer (dry-run in check mode)
     * @param messager       for a single summary note; may be a no-op in check mode
     */
    public static void write(Path moduleRoot,
                             Path vibetagsRoot,
                             Map<String, Path> moduleFiles,
                             Set<String> moduleActive,
                             AnnotationCollector collector,
                             String projectName,
                             String generatedHeader,
                             RoleConfig roles,
                             GuardrailFileWriter writer,
                             Messager messager) {
        if (moduleRoot == null || moduleRoot.equals(vibetagsRoot) || moduleActive.isEmpty()) {
            return; // module is the root, or nothing opted in here
        }

        GuardrailContentBuilder.Result built =
            new GuardrailContentBuilder(collector, moduleActive, projectName, generatedHeader, roles).build();

        boolean hasAnnotations = collector.anyAnnotationsFound();
        int written = 0;
        for (Map.Entry<String, String> entry : built.contentByService.entrySet()) {
            String service = entry.getKey();
            Path filePath = moduleFiles.get(service);
            if (filePath == null) {
                continue;
            }
            // Ignore-files always overwrite; other files only carry the "hasNewRules" flag when this
            // module actually had annotations (mirrors the single-module guard in generateFiles()).
            boolean isIgnoreFile = service.endsWith("_ignore")
                || "aider_ignore".equals(service) || "aiexclude".equals(service);
            writer.writeFileIfChanged(filePath.toString(), entry.getValue(), hasAnnotations || isIgnoreFile);
            written++;
        }

        // Per-class granular rule files under the module directory; cleanup runs after write.
        GranularRulesWriter granular = new GranularRulesWriter(writer);
        Set<String> writtenQNames = granular.writeAll(built.elementRules, moduleFiles, moduleActive, roles);
        granular.cleanupAll(moduleFiles, moduleActive, writtenQNames);

        if (written > 0 && messager != null) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: wrote " + written + " module-scoped file(s) under "
                    + vibetagsRoot.relativize(moduleRoot).toString().replace('\\', '/'));
        }
    }
}
