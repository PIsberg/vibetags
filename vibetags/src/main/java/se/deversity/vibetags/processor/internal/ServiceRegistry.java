package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.VibeTagsLogger;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptor;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptors;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps logical AI-platform service keys to their output file paths and resolves which services
 * are "active" based on which files already exist on disk (file-existence opt-in model).
 */
@AIContext(
    focus = "Maps platform service keys to output file paths; resolves active services by checking file existence on disk",
    avoids = "Creating output files that do not already exist — file presence on disk is the user's explicit opt-in signal"
)
public final class ServiceRegistry {

    /** On-disk file name for the machine-readable @AILocked report. */
    public static final String LOCKS_REPORT_FILE = ".vibetags-locks";

    /** On-disk file name for the lean-indexed root aggregate opt-in. */
    public static final String ROOT_INDEX_FILE = ".vibetags-root-index";

    /**
     * Opt-in keys whose file is a marker rather than something an AI tool reads.
     *
     * <p>The {@code AGENTS.md} sole-file rule asks one question: is {@code AGENTS.md} the only
     * file an AI tool reads in this project. These keys are opt-ins, so they are in
     * {@link #OPT_IN_KEYS}, but none of them answers that question.
     *
     * <ul>
     *   <li>{@code root_index} has no renderer at all; its presence flips how a reactor root
     *       merges its aggregates, and nothing is ever written to the file (#788).</li>
     *   <li>{@code testing} is read on demand by an agent already working on tests, never as a
     *       project instruction file, and is never what an {@code AGENTS.md} pointer points at.</li>
     *   <li>{@code locks_report} is rendered and written, so excluding it is a decision and not an
     *       observation: {@code .vibetags-locks} is JSON Lines for the {@code action/locked-files}
     *       CI guard, and no AI tool loads it as instructions (#800).</li>
     * </ul>
     *
     * <p>Counting any of them made a project whose only AI config file is {@code AGENTS.md} stop
     * having it written the moment it opted in, which is Tier-1 invariant 4 answering wrongly for
     * a reason no diagnostic explains.
     */
    private static final Set<String> MARKER_ONLY_OPT_INS = Set.of("root_index", "testing", "locks_report");

    /**
     * Subset of service keys whose presence on disk activates a service: every entry of
     * {@link PlatformDescriptors#ALL} that is not an implicit child of another one.
     *
     * <p>This was a second hand-kept list of the same keys, in a different order again, and
     * nothing connected it to the path map (<a
     * href="https://github.com/PIsberg/vibetags/issues/762">issue #762</a>). A key in the map and
     * not here was an output the user had no way to opt into; a key here and not in the map was an
     * opt-in that resolved to no path. Both compiled.
     */
    private static final Set<String> OPT_IN_KEYS = optInKeysFromDescriptors();

    private static Set<String> optInKeysFromDescriptors() {
        Set<String> keys = new LinkedHashSet<>();
        for (PlatformDescriptor descriptor : PlatformDescriptors.ALL) {
            if (descriptor.optIn()) {
                keys.add(descriptor.serviceKey());
            }
        }
        return Set.copyOf(keys);
    }

    /**
     * The always-loaded safety file inside a granular directory whose rule files load only on a
     * glob match: Cline's {@code .clinerules/} (issue #648), and Devin Desktop's
     * {@code .devin/rules/} and {@code .windsurf/rules/} (issue #684).
     *
     * <p>Every per-element rule file there loads only when a matching file is in the task's
     * context, so a project on the directory alone had nowhere to keep the six safety buckets always
     * loaded (invariant 6). This file carries them in the shape each tool loads on every request: no
     * front matter for Cline, {@code trigger: always_on} for Devin Desktop.
     *
     * <p>The leading {@code +} is load-bearing. Element stems are {@code [A-Za-z0-9-]}
     * ({@code ElementNaming.granularQName}), role stems {@code [A-Za-z0-9._-]}
     * ({@code RoleConfig.sanitize}) and mirrored stems start with {@code mirrored-}, the same in
     * every granular directory, so no rule file can ever share this name, and the orphan sweep's
     * exclusion of it can never shelter a stale rule file.
     */
    public static final String SAFETY_TIER_FILE = "+vibetags-safety.md";

    /** Cline's safety file, {@link #SAFETY_TIER_FILE} inside {@code .clinerules/} (issue #648). */
    public static final String CLINE_SAFETY_FILE = SAFETY_TIER_FILE;

    private ServiceRegistry() {}

    /**
     * The service keys whose file's presence on disk is the user's opt-in signal, as an
     * immutable copy. Exposed for tooling (the {@code vibetags-cli} {@code init} and
     * {@code doctor} commands) so the opt-in list has exactly one home; the CLI creating a
     * file from this set is the user opting in explicitly — the processor itself still never
     * creates one.
     */
    public static Set<String> optInKeys() {
        return Set.copyOf(OPT_IN_KEYS);
    }

    /**
     * Returns the canonical map of service key → output file path for a given project root.
     */
    public static Map<String, Path> buildServiceFileMap(Path root) {
        Map<String, Path> map = new LinkedHashMap<>();
        for (PlatformDescriptor descriptor : PlatformDescriptors.ALL) {
            map.put(descriptor.serviceKey(), root.resolve(descriptor.relativePath()));
        }
        return map;
    }

    /**
     * Resolves which primary services should have their files written. Only "signal" files
     * (e.g. CLAUDE.md, .cursorrules) are checked; their presence is the opt-in.
     *
     * <p>Special case for {@code AGENTS.md} (the {@code codex} service): it doubles as a
     * near-universal agent-instructions file, and projects that use several AI tools frequently
     * keep {@code AGENTS.md} only as a thin pointer to another tool's file (e.g. {@code CLAUDE.md}).
     * To avoid clobbering such a pointer, {@code AGENTS.md} is treated as a write target only when
     * it is the <em>sole</em> AI config file present. If any other service opted in, {@code codex}
     * is dropped here, which also disables the Codex sidecar config it would otherwise drive.
     *
     * <p><strong>Marker escape hatch.</strong> The sole-file rule exists to protect hand-authored
     * pointers, not to forbid a generated {@code AGENTS.md} outright — a Claude + Codex project
     * could not have one at all. A file that already contains a VibeTags block
     * ({@link GuardrailFileWriter#MARKER_START_MD}) was written by VibeTags in the first place, and
     * {@code GuardrailFileWriter} only ever replaces the region between the markers, so refreshing
     * it cannot destroy anything the user wrote by hand. Such a file therefore stays an active
     * write target even alongside {@code CLAUDE.md}, {@code GEMINI.md} and friends. Paste a
     * {@code VIBETAGS-START} / {@code VIBETAGS-END} comment pair into {@code AGENTS.md} to opt a
     * multi-tool project into a generated Codex file.
     */
    public static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
        boolean codexOptedIn = allServiceFiles.containsKey("codex") && Files.exists(allServiceFiles.get("codex"));
        Set<String> active = resolveActiveServices(allServiceFiles);

        // AGENTS.md is only managed when it is the only AI config file present (see Javadoc); the
        // quiet overload dropped it, so re-emit the explanatory note here for the root resolution.
        if (codexOptedIn && !active.contains("codex")) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: AGENTS.md left untouched because other AI config files are present; "
                + "it is treated as a pointer rather than a generated file. Keep only AGENTS.md "
                + "(remove the other AI config files), or paste a "
                + GuardrailFileWriter.MARKER_START_MD + " / " + GuardrailFileWriter.MARKER_END_MD + " pair into it, "
                + "to have VibeTags manage it.");
        }

        // Deprecated outputs are still written; this is where an opted-in consumer hears that they
        // will stop being written (#641). Raised here rather than in the processor because this
        // method already runs exactly once per compilation for the root, in generateFiles() and in
        // check mode alike, and generateFiles() is @AILocked.
        DeprecatedServices.warnIfOptedIn(messager, VibeTagsLogger.currentFor(rootOf(allServiceFiles)), active);

        if (active.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                "VibeTags: No AI config files found - nothing will be generated.\n" +
                "Create one or more of the following files in your project root to opt in:\n");
            // A deprecated output is left off: this list is what a new user copies from. A directory
            // service carries a trailing '/', because .clinerules is both a deprecated file and a
            // current directory, and a bare name would have a new user touch the deprecated one.
            // Paths are root-relative for the same reason: .greptile/config.json and the deprecated
            // .cody/config.json share a file name.
            Path root = rootOf(allServiceFiles);
            allServiceFiles.entrySet().stream()
                .filter(e -> OPT_IN_KEYS.contains(e.getKey()) && !"root_index".equals(e.getKey())
                    && !DeprecatedServices.keys().contains(e.getKey()))
                .forEach(e -> msg.append("  ").append(optInName(root, e.getValue()))
                    .append(writesDirectory(e.getKey()) ? "/" : "").append('\n'));
            messager.printMessage(Diagnostic.Kind.NOTE, msg.toString());
        }

        return active;
    }

    /**
     * How the opt-in note names {@code path}: relative to the root, with {@code /} separators, so
     * {@code .greptile/config.json} is not listed as a bare {@code config.json} that reads like
     * Cody's, and the two {@code SKILL.md} entries are told apart. Falls back to the file name for a
     * hand-built map with no root to relativise against.
     */
    private static String optInName(@Nullable Path root, Path path) {
        if (root != null && path.startsWith(root)) {
            return root.relativize(path).toString().replace('\\', '/');
        }
        return GuardrailFileWriter.fileName(path);
    }

    /**
     * The root a service map was built for, recovered from {@code CLAUDE.md}, which
     * {@link #buildServiceFileMap} always places directly under it. {@code null} for a hand-built
     * map without that entry, which only costs the log line, never the warning.
     */
    private static @Nullable Path rootOf(Map<String, Path> allServiceFiles) {
        Path claude = allServiceFiles.get("claude");
        return claude == null ? null : claude.getParent();
    }

    /**
     * Quiet resolution — same file-existence opt-in and AGENTS.md sole-file logic as
     * {@link #resolveActiveServices(Messager, Map)}, but emits no diagnostics. Used for per-module
     * output scans, where a module directory with no opt-in files is the common case and must not
     * spam a "nothing will be generated" note for every module in a reactor.
     */
    public static Set<String> resolveActiveServices(Map<String, Path> allServiceFiles) {
        Set<String> active = new HashSet<>();
        allServiceFiles.forEach((key, path) -> {
            if (OPT_IN_KEYS.contains(key) && isOptedIn(key, path)) {
                active.add(key);
            }
        });
        // AGENTS.md is only managed when it is the only AI config file present (see Javadoc),
        // unless it already carries a VibeTags block — a marked file is one VibeTags generated,
        // so refreshing it cannot clobber a hand-authored pointer.
        // TESTING.md does not count as company: no tool reads it as its instruction file, so it
        // is never what an AGENTS.md pointer points at, and counting it would stop a Codex-only
        // project's AGENTS.md being written the moment it opted in to test routing.
        int aiConfigFiles = (int) active.stream().filter(k -> !MARKER_ONLY_OPT_INS.contains(k)).count();
        if (active.contains("codex") && aiConfigFiles > 1
                && !carriesGeneratedBlock(allServiceFiles.get("codex"))) {
            active.remove("codex");
        }
        return active;
    }

    /**
     * True when the service writes a directory of per-element rule files rather than a single file.
     *
     * <p>The one definition of that distinction, for everything that has to know which kind of entry
     * a service path is without looking at the disk: opt-in resolution below, the CLI {@code init}
     * command, and the tests that count and fixture the outputs. The file name cannot answer it,
     * because one path is both: {@code .clinerules} is the {@code cline} file and the
     * {@code cline_granular} directory.
     *
     * <p>It used to read the {@code _granular} suffix, which is a naming convention and not a
     * declaration: a directory service named anything else answered wrongly, and nothing said so.
     * {@link PlatformDescriptor#kind()} is the declaration (<a
     * href="https://github.com/PIsberg/vibetags/issues/762">issue #762</a>). A key that is not a
     * generated output at all answers false, as it did before.
     */
    public static boolean writesDirectory(String key) {
        PlatformDescriptor descriptor = PlatformDescriptors.byKey(key);
        return descriptor != null && descriptor.kind() == PlatformDescriptor.Kind.DIRECTORY;
    }

    /**
     * True when the service is an exclusion list: a {@code *_ignore} file or {@code .aiexclude}.
     * These are rewritten on every build, whether or not the round had annotations.
     *
     * <p>Both writers reach it through {@link WritePlan}, the only place a per-file write decision
     * is made (#766); there is no inline copy left to keep in step.
     */
    public static boolean isIgnoreService(String key) {
        return key.endsWith("_ignore") || "aiexclude".equals(key);
    }

    /**
     * True when a test round's non-safety guardrails leave this service's file for
     * {@code TESTING.md}, once that file is present.
     *
     * <p>The rule picks out the instruction files an agent loads as prose: one rendered file, with
     * VibeTags markers, that is not YAML. The two sides are not symmetric, and the asymmetry is the
     * part to keep. Routing a file that should have stayed whole loses guardrails from it: an ignore
     * file would stop excluding a test file, and a JSON, TOML or YAML tool configuration has no
     * prose in which to say the rest is in {@code TESTING.md}. Failing to route a file only costs
     * the context the feature set out to save. So every doubtful case answers {@code false}.
     *
     * <p>{@code ServiceRoutingContractTest} pins the answer for every key by hand, so a new
     * service fails there until someone decides which side it is on.
     */
    public static boolean routesTestGuardrails(String key) {
        Path file = buildServiceFileMap(Path.of("")).get(key);
        // No platform means no renderer: root_index is a marker file whose extensionless name would
        // otherwise read as "hash markers, no merge shape" and qualify with nothing to route.
        if (file == null || Platform.fromServiceKey(key) == null
                || writesDirectory(key) || isIgnoreService(key)) {
            return false;
        }
        // Decisions rather than consequences of the format: the destination itself, the files that
        // hold only what never moves, the report a CI diff guard reads every lock from, and the
        // llms files, which describe the project to a reader rather than instruct an agent.
        if ("testing".equals(key) || key.endsWith("_safety") || "locks_report".equals(key)
                || key.startsWith("llms")) {
            return false;
        }
        Path name = file.getFileName();
        return name != null
            && GuardrailFileWriter.getMarkersFor(name.toString()) != null
            && PlatformRendererRegistry.mergeShapeFor(key) == null;
    }

    /**
     * True when {@code path} is the <em>kind</em> of filesystem entry service {@code key} writes,
     * i.e. when that entry is an opt-in to this service and not to another one at the same path.
     *
     * <p>This used to be a bare {@code Files.exists}, and the shape of that bug is worth keeping
     * written down. Cline reads {@code .clinerules} as a directory of rule files (its current
     * documented shape) and as a single file (the shape VibeTags wrote first, which its loader still
     * reads). A user following the current docs created the directory, {@code exists()} was true for
     * it, the single-file service activated, and the writer was handed a directory to write a regular
     * file over.
     *
     * <p>A path cannot be both, so the entry's type is an unambiguous signal for which of the two the
     * user meant, and the two services at that path can never both be active.
     */
    public static boolean isOptedIn(String key, Path path) {
        return writesDirectory(key) ? Files.isDirectory(path) : Files.isRegularFile(path);
    }

    /**
     * True when {@code path} already contains a VibeTags-generated markdown block. Unreadable
     * files fall back to {@code false}, i.e. to the conservative sole-file rule.
     */
    private static boolean carriesGeneratedBlock(@Nullable Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.readString(path).contains(GuardrailFileWriter.MARKER_START_MD);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
