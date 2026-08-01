package se.deversity.vibetags.processor.internal;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.RoleConfig;

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
 * There is no cross-<em>module</em> merge — a module's file contains exactly that module's
 * guardrails.
 *
 * <p>It does merge across <em>source sets</em>, though. A module is compiled once per source set,
 * and a {@code test-compile} round cannot see any main source; writing this module's files from
 * that round alone would delete every main-source guardrail it just wrote
 * (<a href="https://github.com/PIsberg/vibetags/issues/330">issue #330</a>). Each source set
 * persists its own sidecar, and the bodies of all sidecars sharing this module's region id are
 * concatenated here, main first.
 *
 * <p>Because the content is built with the <em>module's</em> active-services set, the scoped-rules
 * index composes naturally: a module that opts into both its aggregate file and its granular
 * directory gets an indexed aggregate, exactly like the root.
 */
public final class ModuleOutputWriter {

    private ModuleOutputWriter() {}

    /** Single-module / no-sidecar entry point: builds the module's content and writes it as-is. */
    public static void write(Path moduleRoot,
                             Path vibetagsRoot,
                             Map<String, Path> moduleFiles,
                             Set<String> moduleActive,
                             AnnotationCollector collector,
                             String projectName,
                             String generatedHeader,
                             @Nullable RoleConfig roles,
                             GuardrailFileWriter writer,
                             @Nullable Messager messager) {
        write(moduleRoot, vibetagsRoot, moduleFiles, moduleActive, collector, null, projectName,
            generatedHeader, roles, writer, messager, List.of(), null, null);
    }

    /**
     * Writes {@code collector}'s guardrails to the opted-in files under {@code moduleRoot}.
     *
     * @param moduleRoot     the compiling module's own directory (from {@code compilationRoot()})
     * @param vibetagsRoot   the VibeTags output root; when it equals {@code moduleRoot} this is a
     *                       no-op (the module <em>is</em> the root — root output already covers it)
     * @param moduleFiles    service-key → path map rooted at {@code moduleRoot}
     * @param moduleActive   services opted-in within {@code moduleRoot} (resolved quietly by caller).
     *                       An empty set skips this module's own output but <em>not</em>
     *                       {@link MirrorWriter}: a module with no opt-in of its own can still have
     *                       guardrails another module asked to receive
     * @param collector      this compilation's annotated elements (module-scoped)
     * @param prebuilt       the module-scoped content the caller already rendered (so it could put
     *                       it in the sidecar); {@code null} to build it here
     * @param writer         the shared marker-aware, cache-backed file writer (dry-run in check mode)
     * @param messager       for a single summary note; may be a no-op in check mode
     * @param sidecars       every known sidecar, for the cross-source-set merge and for cleanup
     *                       exclusions; empty when there is no sidecar aggregation
     * @param regionId       this module's region id, or {@code null} to skip the merge
     * @param moduleId       this compilation's own sidecar id, excluded from the cleanup exclusions
     *                       it contributes (they are already covered by what was just written)
     */
    public static void write(Path moduleRoot,
                             Path vibetagsRoot,
                             Map<String, Path> moduleFiles,
                             Set<String> moduleActive,
                             AnnotationCollector collector,
                             GuardrailContentBuilder.@Nullable Result prebuilt,
                             String projectName,
                             String generatedHeader,
                             @Nullable RoleConfig roles,
                             GuardrailFileWriter writer,
                             @Nullable Messager messager,
                             List<ModuleSidecar> sidecars,
                             @Nullable String regionId,
                             @Nullable String moduleId) {
        // Cross-module mirroring runs first and independently of this module's own opt-ins: a module
        // that contributes only to the reactor-root aggregate still has guardrails worth mirroring
        // into a test module that asked for them (issue #312).
        MirrorWriter.write(moduleRoot, vibetagsRoot, collector, projectName, generatedHeader,
            roles, writer, messager);

        if (moduleRoot == null || moduleRoot.equals(vibetagsRoot) || moduleActive.isEmpty()) {
            return; // module is the root, or nothing opted in here
        }

        GuardrailContentBuilder.Result built = prebuilt != null ? prebuilt
            : new GuardrailContentBuilder(collector, moduleActive, projectName, generatedHeader, roles).build();

        boolean hasAnnotations = collector.anyAnnotationsFound();
        int written = 0;
        for (Map.Entry<String, String> entry : built.contentByService.entrySet()) {
            String service = entry.getKey();
            Path filePath = moduleFiles.get(service);
            if (filePath == null) {
                continue;
            }
            String content = entry.getValue();
            if (regionId != null) {
                // Every source set of this module contributed a body; concatenate them so a
                // test-only round adds to the module's file instead of replacing it.
                String merged = ModuleSidecar.mergeModuleBodies(service, sidecars, regionId);
                if (!merged.isBlank()) {
                    content = merged;
                }
            }
            // Ignore-files always overwrite; other files only carry the "hasNewRules" flag when this
            // module actually had annotations (mirrors the single-module guard in generateFiles()).
            boolean isIgnoreFile = service.endsWith("_ignore")
                || "aider_ignore".equals(service) || "aiexclude".equals(service);
            writer.writeFileIfChanged(filePath.toString(), content, hasAnnotations || isIgnoreFile);
            written++;
        }

        // Per-class granular rule files under the module directory; cleanup runs after write, and
        // spares the stems other source sets of this same module recorded in their sidecars.
        GranularRulesWriter granular = new GranularRulesWriter(writer);
        Set<String> writtenStems = granular.writeAll(built.elementRules, moduleFiles, moduleActive, roles);
        Set<String> keep = new LinkedHashSet<>(writtenStems);
        keep.addAll(ModuleSidecar.granularStemsFrom(sidecars, moduleId, regionId));
        Set<String> removed = granular.cleanupAll(moduleFiles, moduleActive, keep);
        new DestructiveRewriteWarner(messager, null).orphanSweep(
            vibetagsRoot.relativize(moduleRoot).toString().replace('\\', '/'), removed, writtenStems);

        if (written > 0 && messager != null) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: wrote " + written + " module-scoped file(s) under "
                    + vibetagsRoot.relativize(moduleRoot).toString().replace('\\', '/'));
        }
    }
}
