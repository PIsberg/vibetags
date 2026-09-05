package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.deversity.vibetags.processor.model.RoleConfig;

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
                             @Nullable RoleConfig roles,
                             GuardrailFileWriter writer,
                             @Nullable Messager messager) {
        write(moduleRoot, vibetagsRoot, collector, projectName, generatedHeader, roles, writer, messager, "");
    }

    /**
     * As above, for one source set of the module.
     *
     * @param sourceSetSuffix {@code ""} for the primary source set, otherwise the
     *        {@code __<sourceSet>} suffix {@code ModuleSidecar.scopedModuleId} gives a sidecar id.
     *        The test round of a module writes and cleans up under its own namespace,
     *        {@code mirrored-<id>__test-}, because a namespace keyed on the module alone had each
     *        round delete what the other had mirrored: the target's rules flapped between the two
     *        halves of every build, and check mode failed on whichever half it ran after.
     */
    public static void write(Path moduleRoot,
                             Path vibetagsRoot,
                             AnnotationCollector collector,
                             String projectName,
                             String generatedHeader,
                             @Nullable RoleConfig roles,
                             GuardrailFileWriter writer,
                             @Nullable Messager messager,
                             String sourceSetSuffix) {
        if (moduleRoot == null || vibetagsRoot == null || moduleRoot.equals(vibetagsRoot)) {
            // No module identity (in-memory/non-javac compile) or the module IS the root: there is
            // no sibling to mirror into, and a root-scoped mirror would just duplicate root output.
            return;
        }
        List<MirrorConfig> targets = MirrorConfig.discover(vibetagsRoot);
        if (targets.isEmpty()) {
            return;
        }

        String regionId = ModuleSidecar.computeModuleId(moduleRoot, vibetagsRoot);
        String moduleId = regionId + sourceSetSuffix;
        Set<String> shadowing = shadowingPrefixes(vibetagsRoot, regionId);
        int written = 0;

        for (MirrorConfig target : targets) {
            // Watch every discovered config, not just the accepting ones: a config edited to *start*
            // accepting this module must invalidate the short-circuit too.
            writer.watchInput(target.configFile());
            Map<String, Path> targetFiles = ServiceRegistry.buildServiceFileMap(target.targetDir());
            Set<String> targetActive = granularOnly(ServiceRegistry.resolveActiveServices(targetFiles));
            if (targetActive.isEmpty()) {
                continue; // target opted into mirroring but has no granular rule directory yet
            }

            String prefix = MIRROR_PREFIX + moduleId + "-";
            GranularRulesWriter granular = new GranularRulesWriter(writer);
            // A sibling whose id begins with this module's id and a dash — core-api beside core —
            // mirrors under a prefix that begins with this module's prefix. Its files are not
            // this module's orphans, and the cleanup below is told so by name.
            Set<String> keep = granular.stemsWithPrefix(targetFiles, targetActive, shadowing);
            if (!target.accepts(moduleRoot)) {
                // Not (or no longer) a source for this target. Anything under this module's own
                // mirror prefix here was written while the config did name it, and is an orphan the
                // moment it stops: the target's config is an allowlist, so a mirrored file whose
                // source module the config does not name cannot be legitimate, whatever else has or
                // has not compiled. Cleaning it is still self-cleanup — the prefix scopes it to this
                // module's own files — so it never reaches a sibling's, which is what stops a cold
                // reactor deleting work it merely has not seen yet (issue #383).
                granular.cleanupMirrored(targetFiles, targetActive, prefix, keep);
                continue;
            }

            GuardrailContentBuilder.Result built =
                new GuardrailContentBuilder(collector, targetActive, projectName, generatedHeader, roles).build();

            Set<String> stems = granular.writeMirrored(
                built.elementRules, targetFiles, targetActive, roles, prefix, target.globs());
            // Cleanup runs after write and only within this module's namespace, so a stale mirror
            // disappears when its annotations do while everything else in the directory survives.
            keep.addAll(stems);
            granular.cleanupMirrored(targetFiles, targetActive, prefix, keep);
            written += stems.size();
        }

        if (written > 0 && messager != null) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: mirrored " + written + " scoped rule file(s) from module " + moduleId
                    + " into " + targets.size() + " mirror target(s).");
        }
    }

    /**
     * Mirror prefixes of every module under {@code root} whose id begins with {@code regionId}
     * and a dash, e.g. {@code mirrored-core-api} when {@code core} is compiling.
     *
     * <p>{@code mirrored-core-} is how {@code mirrored-core-api-...} begins, so core's cleanup
     * read every one of core-api's mirrored files as an orphan of its own and deleted them; the
     * next core-api compile put them back, and every build of core alone took them away again.
     * The siblings are found from the module directories on disk, which is evidence a cold clone
     * has before any sidecar exists (issue #383 forbids arguing from a sidecar's absence). The
     * prefix stops at the sibling's id so that every source set of the sibling is covered.
     */
    static Set<String> shadowingPrefixes(Path root, String regionId) {
        Set<String> prefixes = new LinkedHashSet<>();
        String extended = regionId + "-";
        for (Path dir : moduleDirectories(root)) {
            String id = ModuleSidecar.computeModuleId(dir, root);
            if (id.startsWith(extended)) {
                prefixes.add(MIRROR_PREFIX + id);
            }
        }
        return prefixes;
    }

    /** How deep below the root a sibling module is looked for; one level past mirror discovery. */
    private static final int MODULE_SEARCH_DEPTH = 3;

    /** Directories under {@code root} that carry a build file of their own, {@code root} excluded. */
    private static List<Path> moduleDirectories(Path root) {
        List<Path> modules = new java.util.ArrayList<>();
        collectModules(root.toAbsolutePath().normalize(), 0, modules, new LinkedHashSet<>());
        return modules;
    }

    private static void collectModules(Path dir, int depth, List<Path> out, Set<Path> seen) {
        if (!seen.add(dir)) {
            return; // symlink cycle guard
        }
        if (depth > 0 && dir.equals(ModuleRootResolver.nearestBuildFileAncestor(dir))) {
            out.add(dir);
        }
        if (depth >= MODULE_SEARCH_DEPTH) {
            return;
        }
        try (java.util.stream.Stream<Path> children = java.nio.file.Files.list(dir)) {
            List<Path> dirs = children
                .filter(java.nio.file.Files::isDirectory)
                .filter(p -> {
                    Path name = p.getFileName();
                    return name != null && !MirrorConfig.SKIP_DIRS.contains(name.toString());
                })
                .sorted()
                .toList();
            for (Path child : dirs) {
                collectModules(child, depth + 1, out, seen);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // A directory this build cannot list holds no sibling this build can protect; the
            // ordinary prefix cleanup then behaves as it did before.
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
