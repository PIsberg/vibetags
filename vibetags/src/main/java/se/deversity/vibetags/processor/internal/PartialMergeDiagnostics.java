package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.internal.content.Platform;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The warnings about a merged file that is missing a module's guardrails, or that points at rule
 * files no build will write, raised right after the sidecar read.
 *
 * <p>None of these can be repaired from the compiling module: rendering another module's body or
 * rule file needs that module's annotations, which only its own compilation sees. So each one
 * names what is wrong and the build that fixes it. A WARNING rather than a NOTE, because the file
 * on disk is wrong until then.
 *
 * <p>Every condition here is state still on disk, which is why the processor raises them again on
 * a rebuild that changes nothing (#1063). A warning about the write just made does not belong
 * here.
 */
public final class PartialMergeDiagnostics {

    /** Cap on how many duplicated elements one warning names before summarising. */
    static final int MAX_DUPLICATES_SHOWN = 5;

    private final Path root;
    private final Messager messager;
    private final @Nullable Logger log;

    /**
     * @param root     the VibeTags root the sidecars and opt-in files live under
     * @param messager where the warnings go
     * @param log      the diagnostic log, or {@code null}
     */
    public PartialMergeDiagnostics(Path root, Messager messager, @Nullable Logger log) {
        this.root = root;
        this.messager = messager;
        this.log = log;
    }

    /**
     * Raises every partial-merge warning that applies.
     *
     * <p>The unreadable-sidecar and unusable-mirror checks run for any build, since a reactor with
     * one annotated module still reads and mirrors. The rest compare modules with each other, so a
     * single module, whose own round is by definition current, skips them.
     *
     * @param activeServices       the services opted into at {@code root}
     * @param serviceFiles         service key to output path at {@code root}
     * @param allSidecars          every sidecar this round read, its own included
     * @param stemsThisBuildWrites the root granular stems the compiling module is about to write;
     *                             asked only when a granular directory is opted in
     */
    public void report(Set<String> activeServices, Map<String, Path> serviceFiles,
                       List<ModuleSidecar> allSidecars, Supplier<Set<String>> stemsThisBuildWrites) {
        sidecarsThisRoundCouldNotRead();
        unusableMirrorConfigs();
        if (allSidecars.size() < 2) {
            return; // Single module: its own round is by definition current.
        }
        for (String service : activeServices) {
            serviceOptedInAfterModulesCompiled(service, serviceFiles.get(service), allSidecars);
        }
        granularOptedOutSinceAModuleLastCompiled(activeServices, allSidecars);
        siblingRuleFilesThatAreMissing(activeServices, serviceFiles, allSidecars, stemsThisBuildWrites);
        elementsClaimedByTwoRegions(allSidecars);
    }

    /**
     * Says out loud that this round is writing an aggregate without a module it can see but not read.
     *
     * <p>A sidecar written by a newer processor, or one locked or mid-rename under a parallel
     * build, is skipped and never deleted - correctly, because deleting it would lose that module
     * for good. The gap was that skipping was announced only at DEBUG. The module then vanished
     * from CLAUDE.md and from the granular rule files with nothing said, and the developer met it
     * as an unexplained diff. Issue #590 was that shape reached by an older format; issue #592 is
     * the same shape reached by a newer one, which no change here can prevent because the
     * processor that drops the regions is the older one.
     *
     * <p>A warning rather than an error: the output is stale, not wrong, it repairs itself on the
     * next round once the file is readable, and failing a consumer's compile over a sibling's
     * temporary lock would be worse than the diff this exists to explain.
     */
    private void sidecarsThisRoundCouldNotRead() {
        List<String> unreadable = ModuleSidecar.unreadableSidecarNames(root);
        if (unreadable.isEmpty()) {
            return;
        }
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + unreadable.size() + " module sidecar(s) could not be read this round, so"
                + " the modules they describe are missing from the generated files: "
                + String.join(", ", unreadable)
                + ". A sidecar written by a newer VibeTags than this one (a mixed-version reactor)"
                + " or held open by a parallel build reads this way. Nothing was deleted; build"
                + " again with one version throughout and the regions come back.");
        if (log != null) {
            log.warn("sidecar.unreadable count={} names={}", unreadable.size(), unreadable);
        }
    }

    /**
     * Warns when a platform was opted into after some module last compiled, so the merged file is
     * missing that module's guardrails.
     *
     * <p>Creating an opt-in file at a reactor root activates a platform for the whole build, but a
     * sidecar only carries bodies for the services that were active when its module last ran. An
     * incremental build recompiles the modules whose sources changed and no others, so the new
     * file is assembled from a subset: well-formed, plausible, and missing whole modules. Nothing
     * about it looks wrong, and check mode would compare a later build against it happily.
     */
    private void serviceOptedInAfterModulesCompiled(
            String service, @Nullable Path optIn, List<ModuleSidecar> allSidecars) {
        if (optIn == null || !Files.isRegularFile(optIn)) {
            return;
        }
        // TESTING.md breaks this warning's premise, which is that a current module contributes
        // a body for every active service. A main round renders nothing for `testing` by
        // design, so the main sidecar never carries one however current it is, while the file's
        // own mtime is whatever the test round last wrote. Every routed project would be told
        // on every build that a complete file is missing a module. A warning that is always
        // wrong is worse than none: it teaches the reader to skip the one that is right.
        if (Platform.TESTING.getServiceKey().equals(service)) {
            return;
        }
        long optedInAt;
        try {
            optedInAt = Files.getLastModifiedTime(optIn).toMillis();
        } catch (IOException e) {
            return;
        }
        List<String> behind =
            ModuleSidecar.modulesPredatingOptIn(root, service, optedInAt, allSidecars);
        if (behind.isEmpty()) {
            return;
        }
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + PathStrings.relative(root, optIn)
                + " was opted into after " + String.join(", ", behind)
                + " last compiled, so " + (behind.size() == 1 ? "its guardrails are" : "their guardrails are")
                + " missing from it. Run a full build to complete the file.");
        if (log != null) {
            log.warn("merge.partial service={} modulesBehind={} reason=opted-in-after-last-compile",
                service, behind);
        }
    }

    /**
     * Warns about each {@code .vibetags-mirror} that exists but cannot be used (#1098). Mirroring
     * treats such a config as absent, so its module receives no mirrored rules while the build stays
     * green. Before every module's own early return, since a reactor with one annotated module still
     * mirrors.
     */
    private void unusableMirrorConfigs() {
        for (MirrorConfig.Unusable config : MirrorConfig.unusable(root)) {
            String file = config.relativeTo(root);
            messager.printMessage(Diagnostic.Kind.WARNING,
                "VibeTags: " + file + " is ignored: " + config.reason()
                    + ". Its module receives no mirrored rules until it is fixed.");
            if (log != null) {
                log.warn("mirror.skip file={} reason=unusable-config detail={}", file, config.reason());
            }
        }
    }

    /**
     * Warns when two surviving regions claim the same annotated element.
     *
     * <p>An element belongs to exactly one module, so two regions naming it are the same sources
     * read twice under two identities, and every generated file states that guardrail twice. The
     * region prune retires the loser when one region's elements are entirely covered by a fresher
     * one, but it demands full containment on purpose: a reactor root that compiles sources of its
     * own keeps at least one element no submodule has, and retiring it would drop real guardrails.
     *
     * <p>A leftover sidecar whose element set is a superset only because it is <em>stale</em> falls
     * in the gap: it fails containment by one element, so both regions survive and everything they
     * share is duplicated. That is also why the reported case would not reproduce reliably. When
     * the leftover's elements happen to match the live module's exactly, containment holds and the
     * prune cleans it up; one stale extra and it does not (issue #438).
     *
     * <p>This cannot be repaired here. A sidecar records element ids, not whether their sources
     * still exist, so a stale extra element is indistinguishable from a genuinely root-owned one;
     * and a region carries rendered text rather than a list, so it cannot have one element
     * subtracted from it without the sources that produced it. Naming the duplicate and the
     * sidecar to delete is the remedy, and deleting a sidecar is already the documented escape for
     * a module whose last contribution outlived it.
     */
    private void elementsClaimedByTwoRegions(List<ModuleSidecar> allSidecars) {
        Map<String, Set<String>> regionsByElement = new LinkedHashMap<>();
        for (ModuleSidecar sidecar : allSidecars) {
            for (String element : sidecar.getElementIds()) {
                regionsByElement
                    .computeIfAbsent(element, k -> new LinkedHashSet<>())
                    .add(sidecar.getRegionId());
            }
        }
        List<String> duplicated = new ArrayList<>();
        Set<String> regionsInvolved = new LinkedHashSet<>();
        regionsByElement.forEach((element, regions) -> {
            if (regions.size() > 1) {
                duplicated.add(element + " (" + String.join(" and ", regions) + ")");
                regionsInvolved.addAll(regions);
            }
        });
        if (duplicated.isEmpty()) {
            return;
        }
        // Report at most a few, or a wholesale collision would print one line per element.
        List<String> shown = duplicated.size() > MAX_DUPLICATES_SHOWN
            ? duplicated.subList(0, MAX_DUPLICATES_SHOWN) : duplicated;
        String more = duplicated.size() > shown.size()
            ? " and " + (duplicated.size() - shown.size()) + " more" : "";
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + String.join(", ", shown) + more + " claim the same annotated element"
                + (duplicated.size() == 1 ? "" : "s")
                + " under more than one module, so every generated file states"
                + (duplicated.size() == 1 ? " that guardrail" : " those guardrails")
                + " twice. An element belongs to exactly one module, so one of these regions is a"
                + " leftover from an earlier layout. Delete the sidecar that no longer describes a"
                + " module you build: " + String.join(", ", sidecarNames(regionsInvolved)) + ".");
        if (log != null) {
            log.warn("region.duplicate elements={} regions={} reason=element-claimed-twice",
                duplicated.size(), regionsInvolved);
        }
    }

    /** The on-disk sidecar filenames for the given regions, for a message a reader can act on. */
    private static List<String> sidecarNames(Set<String> regionIds) {
        List<String> names = new ArrayList<>();
        for (String regionId : regionIds) {
            names.add(ModuleSidecar.SIDECAR_PREFIX + regionId);
        }
        return names;
    }

    /**
     * Warns when the aggregate will keep naming a granular rule file that is not on disk and that
     * this build will not write.
     *
     * <p>Each module writes only its own granular files: the write plan comes from the compiling
     * module's elements, and a sibling's stems are added to the cleanup-exclusion set so they are
     * protected from deletion rather than rewritten. That is deliberate, because writing into
     * another module's rule files is the reach that deleted 256 committed files in issue #383,
     * inverted. The consequence is that a file lost to {@code git clean}, a bad merge or a granular
     * opt-out round trip stays lost until its own module recompiles, while the aggregate still
     * points at it as the authoritative source. The guardrail is then stated nowhere: not inline,
     * because the region is collapsed to an index, and not in a rule file, because it is gone.
     *
     * <p>Restoring it here would need the sidecar format to change, because a
     * {@code GranularContribution} carries globs and body but neither the description nor the
     * display name the renderer needs. So this reports instead of repairing (issue #435).
     *
     * <p>The compiling module's own stems are excluded, because this build is about to write them:
     * without that, every first build would warn about the files it is in the middle of creating.
     */
    private void siblingRuleFilesThatAreMissing(Set<String> activeServices,
                                                Map<String, Path> serviceFiles,
                                                List<ModuleSidecar> allSidecars,
                                                Supplier<Set<String>> stemsThisBuildWrites) {
        List<Path> granularDirs = granularDirectories(activeServices, serviceFiles);
        if (granularDirs.isEmpty()) {
            return;
        }
        Set<String> mine = stemsThisBuildWrites.get();
        List<String> missing = new ArrayList<>();
        for (Path dir : granularDirs) {
            addMissingSiblingStems(missing, dir, mine, allSidecars);
        }
        if (missing.isEmpty()) {
            return;
        }
        Collections.sort(missing);
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: the generated files name " + String.join(", ", missing)
                + " as the authoritative rule file(s) for those elements, but they are not on disk"
                + " and this build does not write another module's files. Their guardrails are"
                + " stated nowhere until that module recompiles. Run a full build to restore them.");
        if (log != null) {
            log.warn("granular.missing entries={} reason=sibling-file-absent", missing);
        }
    }

    /** The directory of every active granular service, in the order the services are active. */
    private static List<Path> granularDirectories(Set<String> activeServices, Map<String, Path> serviceFiles) {
        List<Path> dirs = new ArrayList<>();
        for (String service : activeServices) {
            Path dir = serviceFiles.get(service);
            if (dir != null && ServiceRegistry.writesDirectory(service)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /**
     * Adds {@code "<region>'s <stem>"} to {@code missing}, once, for each stem a sibling recorded at
     * the root that has no file in {@code dir} and that this build does not write itself, compared
     * case-folded.
     */
    private static void addMissingSiblingStems(List<String> missing, Path dir, Set<String> mine,
                                               List<ModuleSidecar> allSidecars) {
        // Folded, as GranularMerge folds them: stems that differ only in case are one file on a
        // case-insensitive filesystem and carry one merged contribution everywhere, so whichever
        // spelling is on disk or about to be written states every colliding element (#1181).
        Set<String> written = foldedStems(mine);
        Set<String> present = foldedStems(stemsWithAFileIn(dir));
        for (ModuleSidecar sidecar : allSidecars) {
            // getGranularStems() is the union of two different namespaces: the stems this module
            // writes at the ROOT, and the stems it writes into its OWN granular directory. Only the
            // first kind can be looked for here, because `dir` is a root directory. The two coincide
            // unless a role config renames one of them, which is exactly when they diverge: the
            // root contributions are routed through the root's .vibetags-roles into a shared role
            // file, while the module-scoped ones resolve against the module's own (usually absent)
            // config and keep per-class names. Comparing those per-class names against the root
            // directory reported files as missing that were never meant to be there. See issue #443.
            Set<String> moduleScoped = sidecar.getModuleGranularContributions().keySet();
            for (String stem : sidecar.getGranularStems()) {
                String entry = sidecar.getRegionId() + "'s " + stem;
                String folded = foldedStem(stem);
                boolean accountedFor = moduleScoped.contains(stem) || written.contains(folded) || present.contains(folded);
                if (!accountedFor && !missing.contains(entry)) {
                    missing.add(entry);
                }
            }
        }
    }

    /** {@code stem} as a case-insensitive filesystem and the cross-module merge see it (#525, #1181). */
    private static String foldedStem(String stem) {
        return stem.toLowerCase(Locale.ROOT);
    }

    private static Set<String> foldedStems(Set<String> stems) {
        Set<String> folded = new HashSet<>();
        for (String stem : stems) {
            folded.add(foldedStem(stem));
        }
        return folded;
    }

    /**
     * Every stem that has a file directly inside {@code dir}, whatever the extension: for
     * {@code com.acme.Foo.instructions.md} that is each prefix ending before a dot, so
     * {@code com.acme.Foo} is among them. Empty when the directory cannot be listed.
     *
     * <p>A set rather than a {@code startsWith} scan per stem, because the caller asks once per
     * sidecar stem per service and both counts grow with the number of annotated classes.
     */
    private static Set<String> stemsWithAFileIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        Set<String> stems = new HashSet<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.map(Path::getFileName)
                .filter(Objects::nonNull)
                .map(Path::toString)
                .forEach(name -> {
                    for (int dot = name.indexOf('.'); dot >= 0; dot = name.indexOf('.', dot + 1)) {
                        stems.add(name.substring(0, dot));
                    }
                });
        } catch (IOException | RuntimeException unreadable) {
            return Set.of(); // A diagnostic must never be the thing that fails a build.
        }
        return stems;
    }

    /**
     * The opt-out mirror of {@link #serviceOptedInAfterModulesCompiled}, and the reason these are
     * about partial merges generally rather than only about opt-ins.
     *
     * <p>While a granular directory is opted in, each module renders its region as a scoped-rules
     * index: a list of elements plus the rule file that is authoritative for each. Opting the
     * directory back out deletes those files. The module that recompiles re-renders its region
     * inline, but a module that does not keeps the index it rendered earlier, so the aggregate
     * names rule files that are gone and the guardrail behind them is stated nowhere at all. That
     * is worse than the opt-in window, where a module is merely absent: here the reader is sent
     * somewhere empty and the aggregate still calls it authoritative.
     *
     * <p>The signal is structural rather than a search of rendered text: a sidecar records the
     * granular stems it contributed, so a module still holding stems when nothing is opted in is
     * exactly a module whose region indexes deleted files. Dropping the granular service changes
     * the fingerprint, so each module repairs its own region on its next compile and a full pass
     * closes the window.
     *
     * <p>Deliberately narrow: silent while <em>any</em> granular service is still opted in, because
     * stems are recorded per module rather than per service, so a partial opt-out cannot be told
     * from a live one without a sidecar format change.
     */
    private void granularOptedOutSinceAModuleLastCompiled(
            Set<String> activeServices, List<ModuleSidecar> allSidecars) {
        for (String service : activeServices) {
            if (ServiceRegistry.writesDirectory(service)) {
                return; // Still opted in somewhere; the collapsed shape is correct.
            }
        }
        List<String> behind = allSidecars.stream()
            .filter(sidecar -> !sidecar.getGranularStems().isEmpty())
            .map(ModuleSidecar::getRegionId)
            .distinct()
            .sorted()
            .toList();
        if (behind.isEmpty()) {
            return;
        }
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: the granular rules directory was opted out after "
                + String.join(", ", behind) + " last compiled, so "
                + (behind.size() == 1 ? "its region still points" : "their regions still point")
                + " at rule files that no longer exist. Run a full build to complete the file.");
        if (log != null) {
            log.warn("merge.partial modulesBehind={} reason=granular-opted-out-after-last-compile",
                behind);
        }
    }
}
