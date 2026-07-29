package se.deversity.vibetags.processor.internal;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/**
 * Mirrors one module's granular guardrails into the rule directories of other modules that asked
 * for them (issue #312).
 *
 * <p>Guardrails are normally scoped to the module owning the annotated source. A reactor that keeps
 * its tests in a separate module therefore leaves the code that exercises {@code @AILocked} bridges
 * and {@code @AIPrivacy} key material with no rules in reach — and, worse, silently: nothing in the
 * build reports the gap. A module declares that it wants a sibling's rules by dropping a
 * {@link MirrorConfig#FILE_NAME} file in its own directory; see {@link MirrorConfig} for the format.
 *
 * <p>Mechanics, and why they are what they are:
 * <ul>
 *   <li><b>The target opts in, not the producer.</b> File presence on disk is how every other
 *       VibeTags output is enabled, and the consuming module is the one that knows what it
 *       exercises. The target needs no {@code @AI*} annotations of its own.</li>
 *   <li><b>Filenames are namespaced</b> with {@code mirrored-<sourceModuleId>-}. Modules of a
 *       reactor compile in separate javac invocations, so each source module must be able to clean
 *       up its own stale mirrors without touching the target's own rules or another module's.</li>
 *   <li><b>The config file is watched</b> in the write cache, because it lives in a directory the
 *       compiling module's build fingerprint knows nothing about — without that, editing it would
 *       be invisible to the top-level short-circuit.</li>
 * </ul>
 *
 * <p>Absent any {@code .vibetags-mirror} file, {@link #write} costs one shallow directory listing
 * and changes no output at all.
 */
public final class MirrorWriter {

    /** Reserved filename prefix for mirrored rule files; see {@link GuardrailFileWriter#MIRROR_FILE_PREFIX}. */
    public static final String MIRROR_PREFIX = GuardrailFileWriter.MIRROR_FILE_PREFIX;

    private MirrorWriter() {}

    /**
     * Writes {@code collector}'s granular rules into every module that opted to receive them from
     * {@code moduleRoot}.
     *
     * @param moduleRoot      the compiling module's own directory
     * @param vibetagsRoot    the VibeTags output root (reactor root)
     * @param collector       this compilation's annotated elements (module-scoped)
     * @param roles           the source module's role routing, so mirrored files match its layout
     * @param writer          the shared file writer (dry-run in check mode)
     * @param messager        for a single summary note; may be {@code null} in check mode
     */
    public static void write(Path moduleRoot,
                             Path vibetagsRoot,
                             AnnotationCollector collector,
                             String projectName,
                             String generatedHeader,
                             RoleConfig roles,
                             GuardrailFileWriter writer,
                             Messager messager) {
        if (moduleRoot == null || vibetagsRoot == null || moduleRoot.equals(vibetagsRoot)) {
            // No module identity (in-memory/non-javac compile) or the module IS the root: there is
            // no sibling to mirror into, and a root-scoped mirror would just duplicate root output.
            return;
        }
        List<MirrorConfig> targets = MirrorConfig.discover(vibetagsRoot);
        if (targets.isEmpty()) {
            return;
        }

        String moduleId = ModuleSidecar.computeModuleId(moduleRoot, vibetagsRoot);
        int written = 0;

        for (MirrorConfig target : targets) {
            // Watch every discovered config, not just the accepting ones: a config edited to *start*
            // accepting this module must invalidate the short-circuit too.
            writer.watchInput(target.configFile());
            if (!target.accepts(moduleRoot)) {
                continue;
            }
            Map<String, Path> targetFiles = ServiceRegistry.buildServiceFileMap(target.targetDir());
            Set<String> targetActive = granularOnly(ServiceRegistry.resolveActiveServices(targetFiles));
            if (targetActive.isEmpty()) {
                continue; // target opted into mirroring but has no granular rule directory yet
            }

            GuardrailContentBuilder.Result built =
                new GuardrailContentBuilder(collector, targetActive, projectName, generatedHeader, roles).build();

            String prefix = MIRROR_PREFIX + moduleId + "-";
            GranularRulesWriter granular = new GranularRulesWriter(writer);
            Set<String> stems = granular.writeMirrored(
                built.elementRules, targetFiles, targetActive, roles, prefix, target.globs());
            // Cleanup runs after write and only within this module's namespace, so a stale mirror
            // disappears when its annotations do while everything else in the directory survives.
            granular.cleanupMirrored(targetFiles, targetActive, prefix, stems);
            written += stems.size();
        }

        if (written > 0 && messager != null) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: mirrored " + written + " scoped rule file(s) from module " + moduleId
                    + " into " + targets.size() + " mirror target(s).");
        }
    }

    /**
     * The granular (scoped-rule-directory) services among {@code active}. Mirroring deliberately
     * covers only these: an aggregate file like the target's own {@code CLAUDE.md} is that module's
     * to own, and merging a sibling's guardrails into it is what the sidecar merge already does at
     * the reactor root.
     */
    private static Set<String> granularOnly(Set<String> active) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : active) {
            if (s.endsWith("_granular")) {
                out.add(s);
            }
        }
        return out;
    }
}
