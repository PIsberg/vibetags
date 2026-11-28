package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.VibeTagsLogger;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.processor.internal.content.GranularContribution;
import se.deversity.vibetags.processor.internal.content.GranularPairing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Per-module rendered-body cache for multi-module Maven/Gradle builds.
 *
 * <p>When multiple modules share a single VibeTags root (via {@code vibetags.root}), each module
 * writes its rendered per-service bodies here. Siblings read all sidecars to produce aggregated
 * output that spans the entire project, avoiding the last-writer-wins overwrite problem.
 *
 * <p>File pattern: {@code <root>/.vibetags-mod-<moduleId>}
 *
 * <p><strong>Module id vs. region id.</strong> A module is compiled once per <em>source set</em> —
 * Maven's {@code compile} and {@code test-compile} are two javac invocations that see disjoint
 * sources. Each gets its own sidecar file ({@code .vibetags-mod-core}, {@code
 * .vibetags-mod-core__test}) so neither can overwrite the other's contribution
 * (<a href="https://github.com/PIsberg/vibetags/issues/330">issue #330</a>), but both carry the
 * same <em>region</em> id and are merged back into one {@code VIBETAGS-MODULE} region, so the
 * shared guardrail files still show one region per module.
 *
 * <p>Staleness: a sidecar whose {@code modulePath} no longer resolves to an existing directory
 * under {@code root} is automatically deleted by {@link #readAll(Path)} and excluded from the
 * merged output. Users can also manually delete {@code .vibetags-mod-*} files to force a clean
 * rebuild.
 *
 * <p>That check cannot see one kind of stale sidecar: one whose module path is the root itself,
 * because the root directory always exists. {@link #readAll(Path)} therefore also retires a region
 * whose annotated elements are all claimed by regions of modules nested inside it — the same
 * sources read twice under a less specific identity, which otherwise appears as two byte-identical
 * {@code VIBETAGS-MODULE} regions in every generated file.
 */
@AICore(
    sensitivity = "high",
    note = "The .vibetags-mod-* format is read by modules compiled separately, possibly by another processor version; changing it breaks them"
)
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.OTHER,
    note = "Atomic temp-file moves (ATOMIC_MOVE with plain-move fallback); concurrent saves and reads never tear a sidecar or prune a sibling's (ModuleSidecarAsyncTest proves it)"
)
@AITestDriven(
    coverageGoal = 90,
    framework = AITestDriven.Framework.JUNIT_5,
    mockPolicy = "Write the failing test first; the sidecar format is cross-module law, so tests read and write real .vibetags-mod-* files, never mocks of them"
)
public final class ModuleSidecar {

    public static final String SIDECAR_PREFIX = ".vibetags-mod-";
    /**
     * Format version written into every sidecar header. Bump when the format changes.
     *
     * <p>Version history:
     * <ul>
     *   <li>1 — original format. Module identity was derived from the JVM working directory,
     *       which is the reactor root for every module of an in-process Maven/Gradle build, so
     *       v1 sidecars from reactor builds carry a wrong, shared {@code _root_} identity
     *       (issue #278). They cannot be trusted and are pruned on read.</li>
     *   <li>2 — identical file layout; module identity now derived from the compiled sources
     *       (see {@code ModuleRootResolver}). The bump exists purely to invalidate v1 files.</li>
     * </ul>
     *
     * <p>Still 2 as of the source-set split (issue #330), the safety-digest addition (issue #332)
     * and the granular contributions (issue #365): all three only add <em>keys</em> to the same
     * {@code key=value} layout. A sidecar written
     * by an older processor simply carries none of them and degrades to the previous behaviour,
     * and an older processor reading a newer sidecar sees the extra keys as service bodies whose
     * names ({@code ~...}) match no service and are therefore never rendered. Bumping would have
     * discarded every sibling's contribution on the first mixed-version build for no gain.
     */
    static final int FORMAT_VERSION = 3;

    /**
     * The oldest on-disk shape this reader still accepts, rather than prunes as stale.
     *
     * <p>Version 2 is the pre-trailer format. It is readable in full - the trailer is the only
     * difference - so a reader that dropped it would take that module out of every sibling's
     * output for no reason, which is exactly what issue #590 was.
     */
    static final int MIN_READABLE_VERSION = 2;
    private static final String KEY_FORMAT_VERSION = "# version";
    /**
     * Written last, checked first: the marker that says this file is whole.
     *
     * <p>Without it a cut-short sidecar loaded as a valid one. {@code Base64.getDecoder()} accepts
     * unpadded input, so removing the last seven bytes of a saved sidecar still decoded — to a body
     * that was never saved, which the merge then rendered into every sibling module's aggregate
     * (issue #553). The format carries no length and no checksum, so a torn write is otherwise
     * indistinguishable from a complete file.
     *
     * <p>Appended rather than version-bumped at first, because a bump makes an older processor
     * skip a newer sibling's file: one that predates this line skips any {@code #} key it does not
     * recognise, so it reads a file carrying the trailer exactly as before
     * ({@code anUnknownCommentLineIsSkippedRatherThanParsed} pins that rule). The cost was
     * described as the other direction being "skipped as unreadable until its module recompiles",
     * and that cost was underestimated: skipping a sibling drops it from {@code readAll}, the round
     * then sees one region and merges as single-module, and every other module's region vanishes
     * from the aggregate and from the granular role files. On a project that commits its sidecars,
     * the first build after upgrading did exactly that, silently (issue #590).
     *
     * <p>So the version carries the promise instead. {@link #FORMAT_VERSION} 3 writes a trailer and
     * is held to it; {@link #MIN_READABLE_VERSION} 2 is the pre-trailer shape, read in full without
     * one. A missing trailer is evidence of a torn write only for a version that always writes one,
     * which keeps issue #553's protection intact for every file this processor writes while a file
     * written before the trailer existed is read rather than discarded. Either way, never deleted.
     */
    static final String TRAILER = "# end";

    /** One buffer per sidecar, reused for the whole file; see {@code SidecarScans.scanUnreadable}. */
    static final int SCAN_BUFFER_CHARS = 8192;
    static final String KEY_MODULE_ID = "moduleId";
    static final String KEY_MODULE_PATH = "modulePath";
    private static final String KEY_REGION_ID = "regionId";

    /** Reserved body-key prefix; never a service key, so older readers ignore these entries. */
    private static final String RESERVED_PREFIX = "~";
    /** Key holding the granular rule stems this module+source-set wrote (newline separated). */
    private static final String KEY_GRANULAR_STEMS = "~granular";
    /** Key prefix for a module's own (nested) per-service body. */
    private static final String KEY_MODULE_BODY_PREFIX = "~mod~";
    /** Key prefix for the safety-tier digest used by the lean indexed reactor root. */
    private static final String KEY_INDEX_DIGEST_PREFIX = "~idx~";
    /**
     * Key prefix for the body a routed test round would have written to a service's file without
     * {@code TESTING.md}. Read only once that file is gone, so deleting it and rebuilding the main
     * sources alone loses no test guardrail.
     */
    static final String KEY_UNROUTED_BODY_PREFIX = "~tfull~";
    /** Key holding the annotated element ids this module+source-set contributed. */
    private static final String KEY_ELEMENT_IDS = "~elements";
    /**
     * Key holding the source roots this module+source-set compiled from, relative to the VibeTags
     * root with forward slashes, newline separated. Read only by {@link PartialRoundDetector}: a
     * root outside the module directory is otherwise never walked, and a round compiled from the
     * module's own root read that root's elements as deleted (#1065). Reserved like the rest, so an
     * older reader leaves it unstored.
     */
    private static final String KEY_SOURCE_ROOTS = "~roots";
    /**
     * Key prefix for one granular rule file's contribution from this module+source-set, keyed by
     * stem. Read back by {@link #mergeGranular} so a rule file several modules write ends up with
     * all of their rules instead of the last writer's (issue #365).
     */
    private static final String KEY_GRANULAR_UNIT_PREFIX = "~gran~";
    /** As above, for the module's own (nested) granular output; merged across source sets only. */
    private static final String KEY_MODULE_GRANULAR_UNIT_PREFIX = "~modgran~";
    /**
     * Key prefix for a granular contribution's heading name and description, keyed by stem. A key
     * of its own rather than a field inside the {@code ~gran~} value: that value's shape is what an
     * older sibling parses, and a field added to it would be read as glob or body text, whereas an
     * unknown reserved key is one such a reader leaves unstored (issue #579).
     */
    private static final String KEY_GRANULAR_NAMING_PREFIX = "~granname~";
    /** As above, for the module's own (nested) granular output. */
    private static final String KEY_MODULE_GRANULAR_NAMING_PREFIX = "~modgranname~";

    /** Separator between a module id and its non-primary source set. */
    static final String SOURCE_SET_SEPARATOR = "__";

    static final int MULTI_MODULE_THRESHOLD = 2;

    /** Cap on the readable half of an out-of-tree module id; the rest is a hash. */
    private static final int MAX_READABLE_ID = 24;

    /** Module sub-marker embedded inside the outer VIBETAGS-START/END block. */
    public static final String SUB_MARKER_MD_FORMAT = "<!-- VIBETAGS-MODULE: %s -->";
    public static final String SUB_MARKER_MD_END_FORMAT = "<!-- VIBETAGS-MODULE-END: %s -->";
    public static final String SUB_MARKER_HASH_FORMAT = "# VIBETAGS-MODULE: %s";
    public static final String SUB_MARKER_HASH_END_FORMAT = "# VIBETAGS-MODULE-END: %s";

    private final String moduleId;
    private final String modulePath;
    private final String regionId;
    private final Map<String, String> bodies = new LinkedHashMap<>();

    /** This module+source-set's own nested output bodies (service key → rendered body). */
    private final Map<String, String> moduleBodies = new LinkedHashMap<>();

    /**
     * Safety-tier-only renderings of the aggregate services, kept inline in the lean indexed
     * reactor root next to the pointer (issue #332). Empty unless the root opted into the index.
     */
    private final Map<String, String> indexDigests = new LinkedHashMap<>();

    /**
     * Service key to the body a routed test round would have written without {@code TESTING.md}.
     * Persisted under {@link #KEY_UNROUTED_BODY_PREFIX}; empty for every unrouted round, so those
     * sidecars stay byte-identical to what they were before the key existed.
     */
    private final Map<String, String> unroutedBodies = new LinkedHashMap<>();

    /**
     * Granular rule stems (filename minus extension) this module+source-set wrote. Sibling
     * compilations read them so orphan cleanup never deletes a file it simply could not see —
     * neither another module's, nor another source set's (issue #330).
     */
    private final Set<String> granularStems = new LinkedHashSet<>();

    /**
     * What this module+source-set contributes to each granular rule file it writes, keyed by stem.
     *
     * <p>Distinct from {@link #granularStems}, which only names the files: the stems answer "may
     * this file be deleted?", these answer "what belongs in it?". A stem is not owned by one module
     * — a role in a reactor-root {@code .vibetags-roles} routes classes from several modules into
     * one file, and every one of them resolves the same path — so without the content the last
     * module to compile simply replaced the file (issue #365).
     */
    private final Map<String, GranularContribution> granularUnits = new LinkedHashMap<>();

    /** As above for the module's own nested granular output, merged across source sets only. */
    private final Map<String, GranularContribution> moduleGranularUnits = new LinkedHashMap<>();

    /**
     * Every annotated element this module+source-set contributed, by stable id. Unlike
     * {@link #granularStems} this is populated whether or not a granular service is active, because
     * it answers a different question: what did this module record last time? A round whose element
     * set shares nothing with the previous one is replacing a module's guardrails, not editing them
     * (see {@code DestructiveRewriteWarner}).
     */
    private final Set<String> elementIds = new LinkedHashSet<>();

    /** The source roots behind {@link #KEY_SOURCE_ROOTS}; empty in a sidecar written before #1065. */
    private final Set<String> sourceRoots = new LinkedHashSet<>();

    // --- Lean indexed-root state (transient; NEVER persisted by save(), so the on-disk sidecar
    // format is unchanged). Populated by readAll() only when the reactor root opts into the lean
    // index via a .vibetags-root-index file. ---

    /** True when the reactor root opted into the lean indexed aggregate (see {@code root_index}). */
    private boolean rootIndexMode;

    /**
     * For a non-root module in index mode: aggregate service key → the pointer text that should
     * REPLACE this module's embedded body in the reactor-root aggregate. Present only for the four
     * aggregate services with a granular sibling ({@code claude}/{@code cursor}/{@code windsurf}/
     * {@code copilot}) AND only when this module actually emits its own per-module output for that
     * service (so nothing is ever dropped — a module with no own output keeps its embedded body).
     */
    private final Map<String, String> indexPointers = new LinkedHashMap<>();

    /**
     * True when {@code readAll} found no {@code TESTING.md} opt-in at the root. Transient like the
     * index state above: it describes the disk now, not the round that wrote this sidecar.
     */
    private boolean testingWithdrawn;

    /**
     * @param moduleId   filename-safe identifier (e.g. {@code "module_graph"}, {@code "_root_"})
     * @param modulePath path of the module root relative to the VibeTags root
     *                   (e.g. {@code "module-graph"}); {@code ""} for the root module
     */
    public ModuleSidecar(String moduleId, String modulePath) {
        this(moduleId, modulePath, moduleId);
    }

    /**
     * @param regionId the id under which this sidecar's body is merged into the shared files.
     *                 Several sidecars (one per source set) share one region id.
     */
    public ModuleSidecar(String moduleId, String modulePath, String regionId) {
        this.moduleId = moduleId;
        this.modulePath = portable(modulePath);
        this.regionId = regionId;
    }

    /**
     * {@code modulePath} with forward slashes. A sidecar is read by every module's compile, and
     * one checkout can be built from Windows and from Linux (WSL, a bind mount): a path Windows
     * wrote with backslashes resolves on Linux as a single filename, reads as a departed module,
     * and is pruned with its stems (#868). Forward slashes resolve on both, so they are what is
     * written, and a backslashed value already on disk is read the same way.
     */
    private static String portable(String modulePath) {
        return PathStrings.forwardSlashes(modulePath);
    }

    /** Stores the rendered body for {@code serviceKey} if non-blank. */
    public void putBody(String serviceKey, String body) {
        if (body != null && !body.isBlank()) {
            bodies.put(serviceKey, body);
        }
    }

    /** Stores this module's own (nested output) rendered body for {@code serviceKey} if non-blank. */
    public void putModuleBody(String serviceKey, String body) {
        if (body != null && !body.isBlank()) {
            moduleBodies.put(serviceKey, body);
        }
    }

    /** Stores the safety-tier digest kept inline beside the lean root's pointer for this module. */
    public void putIndexDigest(String serviceKey, String body) {
        if (body != null && !body.isBlank()) {
            indexDigests.put(serviceKey, body);
        }
    }

    /**
     * Stores what a routed test round would have rendered for {@code serviceKey} had
     * {@code TESTING.md} not been there; see {@link #reactorBody}.
     */
    public void putUnroutedBody(String serviceKey, String body) {
        if (body != null && !body.isBlank()) {
            unroutedBodies.put(serviceKey, body);
        }
    }

    /** Records the granular rule stems this module+source-set wrote this run. */
    public void setGranularStems(Set<String> stems) {
        granularStems.clear();
        if (stems != null) {
            granularStems.addAll(stems);
        }
    }

    /** Records this module+source-set's contribution to one shared granular rule file. */
    public void putGranularContribution(String stem, GranularContribution contribution) {
        if (contribution != null && !contribution.isEmpty()) {
            granularUnits.put(stem, contribution);
        }
    }

    /** Records this module+source-set's contribution to one of its own nested granular files. */
    public void putModuleGranularContribution(String stem, GranularContribution contribution) {
        if (contribution != null && !contribution.isEmpty()) {
            moduleGranularUnits.put(stem, contribution);
        }
    }

    /** Records the annotated element ids this module+source-set contributed this run. */
    public void setElementIds(Set<String> ids) {
        elementIds.clear();
        if (ids != null) {
            elementIds.addAll(ids);
        }
    }

    /** Records the source roots this round compiled from, relative to the VibeTags root. */
    public void setSourceRoots(Collection<String> roots) {
        sourceRoots.clear();
        for (String r : roots) {
            if (!r.isBlank()) sourceRoots.add(portable(r));
        }
    }

    public String getModuleId() { return moduleId; }
    public String getModulePath() { return modulePath; }
    public String getRegionId() { return regionId; }
    public Map<String, String> getBodies() { return Collections.unmodifiableMap(bodies); }
    public Map<String, String> getModuleBodies() { return Collections.unmodifiableMap(moduleBodies); }
    public Map<String, String> getUnroutedBodies() { return Collections.unmodifiableMap(unroutedBodies); }
    public Set<String> getGranularStems() { return Collections.unmodifiableSet(granularStems); }
    public Set<String> getElementIds() { return Collections.unmodifiableSet(elementIds); }
    public Set<String> getSourceRoots() { return Collections.unmodifiableSet(sourceRoots); }
    public Map<String, GranularContribution> getGranularContributions() {
        return Collections.unmodifiableMap(granularUnits);
    }
    public Map<String, GranularContribution> getModuleGranularContributions() {
        return Collections.unmodifiableMap(moduleGranularUnits);
    }

    /** Returns true when this sidecar has at least one non-empty body. */
    public boolean hasContent() { return !bodies.isEmpty(); }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /** Persists this sidecar atomically to {@code <root>/<SIDECAR_PREFIX><moduleId>}. */
    public void save(Path root) throws IOException {
        Path target = root.resolve(SIDECAR_PREFIX + moduleId);
        String sb = serialized();
        if (sameAsOnDisk(target, sb)) {
            // Rewriting identical bytes moved the file's mtime, and the mtime is part of the
            // stamp every sibling recorded, so each module's full round guaranteed the next
            // module's and a no-op reactor rebuild never converged (issue #556). Unchanged
            // content leaves the file, and the stamp, exactly as the siblings last saw it.
            return;
        }

        // Through a unique temp name rather than `<sidecar>.tmp`: two javac invocations for one
        // module id (a build and an IDE compiling the same module at once) otherwise truncate each
        // other's temp file.
        AtomicFileReplacer.replace(target, sb);
    }

    /** The bytes {@link #save} writes, trailer included. */
    private String serialized() {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_FORMAT_VERSION).append('=').append(FORMAT_VERSION).append('\n')
            .append(KEY_MODULE_ID).append('=').append(moduleId).append('\n')
            .append(KEY_MODULE_PATH).append('=').append(modulePath).append('\n')
            .append(KEY_REGION_ID).append('=').append(regionId).append('\n');
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            appendEncoded(sb, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : moduleBodies.entrySet()) {
            appendEncoded(sb, KEY_MODULE_BODY_PREFIX + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : indexDigests.entrySet()) {
            appendEncoded(sb, KEY_INDEX_DIGEST_PREFIX + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : unroutedBodies.entrySet()) {
            appendEncoded(sb, KEY_UNROUTED_BODY_PREFIX + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, GranularContribution> entry : granularUnits.entrySet()) {
            appendEncoded(sb, KEY_GRANULAR_UNIT_PREFIX + entry.getKey(), entry.getValue().serialize());
            if (entry.getValue().isNamed()) {
                appendEncoded(sb, KEY_GRANULAR_NAMING_PREFIX + entry.getKey(),
                    entry.getValue().serializeNaming());
            }
        }
        for (Map.Entry<String, GranularContribution> entry : moduleGranularUnits.entrySet()) {
            appendEncoded(sb, KEY_MODULE_GRANULAR_UNIT_PREFIX + entry.getKey(), entry.getValue().serialize());
            if (entry.getValue().isNamed()) {
                appendEncoded(sb, KEY_MODULE_GRANULAR_NAMING_PREFIX + entry.getKey(),
                    entry.getValue().serializeNaming());
            }
        }
        if (!granularStems.isEmpty()) {
            appendEncoded(sb, KEY_GRANULAR_STEMS, String.join("\n", granularStems));
        }
        if (!elementIds.isEmpty()) {
            appendEncoded(sb, KEY_ELEMENT_IDS, String.join("\n", elementIds));
        }
        if (!sourceRoots.isEmpty()) {
            appendEncoded(sb, KEY_SOURCE_ROOTS, String.join("\n", sourceRoots));
        }

        sb.append(TRAILER).append('\n');
        return sb.toString();
    }

    /** Whether {@code target} already holds exactly {@code content}; false when unreadable. */
    private static boolean sameAsOnDisk(Path target, CharSequence content) {
        try {
            return Files.isRegularFile(target)
                && Files.readString(target, StandardCharsets.UTF_8).contentEquals(content);
        } catch (IOException | RuntimeException unreadable) {
            return false; // could not compare, so write: a stale sidecar is the worse outcome
        }
    }

    private static void appendEncoded(StringBuilder sb, String key, String value) {
        sb.append(key).append('=')
          .append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
          .append('\n');
    }

    /** What opens the one comment line a reader acts on; the format version follows it. */
    static final String VERSION_HEADER = KEY_FORMAT_VERSION + "=";

    /**
     * What a {@code # version=} header says about its file. The three readers that reach a verdict
     * on a sidecar ({@link #load}, {@link SummaryScan} and {@link TrailerScan}) all take the answer
     * from here, so none of them can read a header its own way (#1154).
     *
     * <p>{@link #readFormatVersionHeader} still reads the number itself. It names why a file was
     * already refused, and for that an older format and a header that is not a number are two
     * different reasons, where here they are one verdict.
     */
    enum FormatVersion {
        /** Newer than this processor reads: a newer sibling's file, skipped and never deleted. */
        FUTURE,
        /** Older than {@link #MIN_READABLE_VERSION}, or not a number: stale, and pruned. */
        STALE,
        /** Readable, and written before the trailer existed, so a missing one says nothing. */
        PRE_TRAILER,
        /** Readable, and held to its {@link #TRAILER}: without one the write was cut short. */
        PROMISES_TRAILER;

        /** Reads what follows {@link #VERSION_HEADER} on a header line. */
        static FormatVersion of(String number) {
            try {
                int version = Integer.parseInt(number.trim());
                if (version > FORMAT_VERSION) {
                    return FUTURE;
                }
                if (version < MIN_READABLE_VERSION) {
                    return STALE;
                }
                return version >= FORMAT_VERSION ? PROMISES_TRAILER : PRE_TRAILER;
            } catch (NumberFormatException malformed) {
                return STALE;
            }
        }
    }

    /**
     * What a {@code key=value} line of a sidecar holds, told from its key. This is the one place
     * the format's keys are told apart: {@link #load} and {@link SummaryScan} both ask {@link #of},
     * so the two readers cannot classify a key differently.
     *
     * <p>Declaration order is match order, and two positions in it matter: {@link #RESERVED} comes
     * after every key that starts with its prefix, and {@link #SERVICE_BODY}, which is whatever no
     * other kind claims, is last.
     */
    enum LineKind {
        MODULE_ID(KEY_MODULE_ID, Claim.WHOLE_KEY),
        MODULE_PATH(KEY_MODULE_PATH, Claim.WHOLE_KEY),
        REGION_ID(KEY_REGION_ID, Claim.WHOLE_KEY),
        GRANULAR_STEMS(KEY_GRANULAR_STEMS, Claim.WHOLE_KEY),
        ELEMENT_IDS(KEY_ELEMENT_IDS, Claim.WHOLE_KEY),
        SOURCE_ROOTS(KEY_SOURCE_ROOTS, Claim.WHOLE_KEY),
        MODULE_GRANULAR_NAMING(KEY_MODULE_GRANULAR_NAMING_PREFIX, Claim.PREFIX),
        GRANULAR_NAMING(KEY_GRANULAR_NAMING_PREFIX, Claim.PREFIX),
        MODULE_GRANULAR_UNIT(KEY_MODULE_GRANULAR_UNIT_PREFIX, Claim.PREFIX),
        GRANULAR_UNIT(KEY_GRANULAR_UNIT_PREFIX, Claim.PREFIX),
        MODULE_BODY(KEY_MODULE_BODY_PREFIX, Claim.PREFIX),
        INDEX_DIGEST(KEY_INDEX_DIGEST_PREFIX, Claim.PREFIX),
        UNROUTED_BODY(KEY_UNROUTED_BODY_PREFIX, Claim.PREFIX),
        /** A reserved key this version does not define, written by a newer processor. */
        RESERVED(RESERVED_PREFIX, Claim.PREFIX),
        /** A service's reactor body. A service key is no constant, so this is every other key. */
        SERVICE_BODY("", Claim.PREFIX);

        /** How a kind claims a key: by being all of it, or by opening it. */
        private enum Claim { WHOLE_KEY, PREFIX }

        /** Every kind that claims keys by name, in match order: all but {@link #SERVICE_BODY}. */
        private static final LineKind[] NAMED =
            java.util.EnumSet.complementOf(java.util.EnumSet.of(SERVICE_BODY)).toArray(new LineKind[0]);

        private final String key;
        private final Claim claim;

        LineKind(String key, Claim claim) {
            this.key = key;
            this.claim = claim;
        }

        static LineKind of(String lineKey) {
            for (LineKind kind : NAMED) {
                if (kind.claims(lineKey)) {
                    return kind;
                }
            }
            return SERVICE_BODY;
        }

        private boolean claims(String lineKey) {
            return claim == Claim.WHOLE_KEY ? key.equals(lineKey) : lineKey.startsWith(key);
        }

        /** What {@code lineKey} names past this kind's prefix: a service key or a rule-file stem. */
        String nameIn(String lineKey) {
            return lineKey.substring(key.length());
        }
    }

    /**
     * Sentinel returned by {@link #load(Path)} for a sidecar written in a newer format than this
     * processor understands. Distinct from {@code null} (malformed): a future-version sidecar is
     * owned by a sibling module compiled with a newer processor and must be skipped, not deleted.
     */
    static final ModuleSidecar FUTURE_VERSION = new ModuleSidecar("_future_", "");

    /**
     * Sentinel returned by {@link #load(Path)} when the file could not be read at all. Also
     * distinct from {@code null}: failing to read a file is never evidence that its content is
     * garbage, and the caller deletes on {@code null}.
     *
     * <p>This is not hypothetical. Windows refuses to open a file that another process is renaming
     * over, which is exactly what a sibling module's {@code save()} does in a parallel reactor. A
     * reader that folded that {@code AccessDeniedException} into "malformed" deleted a valid
     * sidecar and took that module out of the merged output until it recompiled.
     */
    static final ModuleSidecar UNREADABLE = new ModuleSidecar("_unreadable_", "");

    /**
     * Loads a sidecar from {@code path}. Returns {@code null} if the file's <em>content</em> is
     * malformed or was written in an older format than {@link #FORMAT_VERSION} (stale — see the
     * version history on {@code FORMAT_VERSION}; the caller prunes it), {@link #FUTURE_VERSION} if
     * its {@code # version} header is newer than {@link #FORMAT_VERSION} (owned by a newer sibling
     * — skipped but never deleted), or {@link #UNREADABLE} if the file could not be read at all
     * (skipped, and likewise never deleted).
     */
    static @Nullable ModuleSidecar load(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            // Could not read it — that says nothing about the content. See UNREADABLE.
            return UNREADABLE;
        }
        return new LineReader().read(lines);
    }

    /**
     * The state {@link #load} keeps while it reads a sidecar's lines in file order, and the verdict
     * it reaches from them.
     *
     * <p>The first refusal in file order decides. A version header this processor will not read
     * ends the read at that line, and a value that will not decode ends it at that value.
     * {@link SummaryScan} applies the same rules to the same lines without holding a body, and
     * {@code ModuleSidecarOnRecordAgreementTest} holds the two to one verdict.
     */
    private static final class LineReader {

        /** What the last version header read says; {@code null} until there is one. */
        private @Nullable FormatVersion version;
        private @Nullable String moduleId;
        private String modulePath = "";
        private @Nullable String regionId;
        private final Map<String, String> bodies = new LinkedHashMap<>();
        private final Map<String, String> moduleBodies = new LinkedHashMap<>();
        private final Map<String, String> indexDigests = new LinkedHashMap<>();
        private final Map<String, String> unroutedBodies = new LinkedHashMap<>();
        private final Set<String> granularStems = new LinkedHashSet<>();
        private final Set<String> elementIds = new LinkedHashSet<>();
        private final Set<String> sourceRoots = new LinkedHashSet<>();
        private final Map<String, GranularContribution> granularUnits = new LinkedHashMap<>();
        private final Map<String, GranularContribution> moduleGranularUnits = new LinkedHashMap<>();
        private final Map<String, String> granularNaming = new LinkedHashMap<>();
        private final Map<String, String> moduleGranularNaming = new LinkedHashMap<>();

        /** The verdict {@link #load} documents, for a file that could be read to its end. */
        @Nullable ModuleSidecar read(List<String> lines) {
            try {
                for (String line : lines) {
                    accept(line);
                    // Refuse to (mis-)parse a future format, and treat an older one as stale.
                    if (version == FormatVersion.FUTURE) {
                        return FUTURE_VERSION;
                    }
                    if (version == FormatVersion.STALE) {
                        return null;
                    }
                }
            } catch (IllegalArgumentException undecodable) {
                // Read it fine, but a value is not decodable. With no trailer that is a write cut off
                // inside the value, the same torn write the check below reports, and must not be
                // pruned for it. With the trailer it is genuinely corrupt, so the caller prunes.
                return isTorn(lines) ? UNREADABLE : null;
            }
            // Before the identity check, because that one deletes: a file cut short can lose its
            // moduleId line as easily as its last body, and pruning a module's sidecar over a torn
            // write takes that module out of every sibling's output until it recompiles.
            if (isTorn(lines)) {
                return UNREADABLE;
            }
            String id = moduleId;
            return id == null || version == null ? null : toSidecar(id);
        }

        private void accept(String line) {
            if (line.startsWith("#")) {
                // The version header is the only comment acted on. Any other is skipped, which is
                // what lets a newer writer add one without losing its older readers; see TRAILER.
                if (line.startsWith(VERSION_HEADER)) {
                    version = FormatVersion.of(line.substring(VERSION_HEADER.length()));
                }
                return;
            }
            int eq = line.indexOf('=');
            if (eq >= 0) {
                store(line.substring(0, eq), line.substring(eq + 1));
            }
        }

        private void store(String key, String value) {
            LineKind kind = LineKind.of(key);
            String name = kind.nameIn(key);
            switch (kind) {
                case MODULE_ID -> { moduleId = value; }
                case MODULE_PATH -> { modulePath = value; }
                case REGION_ID -> { regionId = value; }
                case GRANULAR_STEMS -> addNonBlankLines(decode(value), granularStems);
                case ELEMENT_IDS -> addNonBlankLines(decode(value), elementIds);
                case SOURCE_ROOTS -> addNonBlankLines(portable(decode(value)), sourceRoots);
                case MODULE_GRANULAR_NAMING -> moduleGranularNaming.put(name, decode(value));
                case GRANULAR_NAMING -> granularNaming.put(name, decode(value));
                case MODULE_GRANULAR_UNIT -> putParsedContribution(moduleGranularUnits, name, decode(value));
                case GRANULAR_UNIT -> putParsedContribution(granularUnits, name, decode(value));
                case MODULE_BODY -> moduleBodies.put(name, decode(value));
                case INDEX_DIGEST -> indexDigests.put(name, decode(value));
                case UNROUTED_BODY -> unroutedBodies.put(name, decode(value));
                case RESERVED -> {
                    // Recognised and deliberately not stored, nor even decoded. Above all it is
                    // kept out of SERVICE_BODY, which would render it into somebody's guardrail
                    // file. Doing nothing here is the point.
                }
                case SERVICE_BODY -> bodies.put(name, decode(value));
            }
        }

        /**
         * Whether the write that produced these lines was cut short. Only a version that promises a
         * trailer may be judged by one. A version-2 file has none to lose, so its absence says
         * nothing about whether the write finished, and treating it as torn is what silently
         * reduced a reactor to one region (issue #590).
         */
        private boolean isTorn(List<String> lines) {
            return version == FormatVersion.PROMISES_TRAILER && !isWhole(lines);
        }

        private ModuleSidecar toSidecar(String id) {
            // Sidecars written before the source-set split carry no regionId; their module id IS
            // the region id, which is exactly what they meant.
            String region = regionId != null && !regionId.isBlank() ? regionId : id;
            ModuleSidecar sidecar = new ModuleSidecar(id, modulePath, region);
            sidecar.bodies.putAll(bodies);
            sidecar.moduleBodies.putAll(moduleBodies);
            sidecar.indexDigests.putAll(indexDigests);
            sidecar.unroutedBodies.putAll(unroutedBodies);
            sidecar.granularStems.addAll(granularStems);
            sidecar.elementIds.addAll(elementIds);
            sidecar.sourceRoots.addAll(sourceRoots);
            sidecar.granularUnits.putAll(named(granularUnits, granularNaming));
            sidecar.moduleGranularUnits.putAll(named(moduleGranularUnits, moduleGranularNaming));
            return sidecar;
        }

        /**
         * {@code units} with each naming line applied to the contribution of its stem. Done once
         * the whole file is in, because a naming line may precede or follow its contribution.
         */
        private static Map<String, GranularContribution> named(Map<String, GranularContribution> units,
                                                               Map<String, String> naming) {
            naming.forEach((stem, serialized) ->
                units.computeIfPresent(stem, (unused, unit) -> unit.withNaming(serialized)));
            return units;
        }
    }

    /**
     * {@link #load} reduced to what {@link #onRecord} reads: the same verdict for the same file, but
     * a sidecar it returns carries only its identity, its element ids and, per service, whether its
     * unrouted body is blank (a placeholder when it is not, empty when it is).
     *
     * <p>No other value is kept, and none is decoded to a string: each is checked for decodability
     * as it streams past, because a value {@code load} cannot decode makes the whole file
     * {@code null} and that has to stay true here. A summary is never merged or rendered; it leaves
     * this class only as an {@link OnRecord}.
     *
     * @param readUnrouted whether to judge the unrouted bodies at all. {@code false} checks them for
     *     decodability only, for a root where {@code TESTING.md} is opted in and the answer is no.
     */
    static @Nullable ModuleSidecar loadSummary(Path path, boolean readUnrouted) {
        return SidecarScans.summary(path, readUnrouted);
    }

    /**
     * The sidecar a {@link #loadSummary} read stands for: identity, element ids, and per service the
     * unrouted body's blankness, kept as given (an empty value included), which
     * {@link #putUnroutedBody} would drop.
     */
    static ModuleSidecar summarized(String moduleId, String modulePath, String regionId,
                                    Collection<String> elementIds, Map<String, String> unrouted) {
        ModuleSidecar s = new ModuleSidecar(moduleId, modulePath, regionId);
        s.elementIds.addAll(elementIds);
        s.unroutedBodies.putAll(unrouted);
        return s;
    }

    /**
     * Stores one parsed granular contribution, dropping a value that is not in the serialized
     * shape. A malformed entry is skipped rather than guessed at: the compiling module then falls
     * back to its own rendering for that file, which is the behaviour before the merge existed.
     */
    private static void putParsedContribution(Map<String, GranularContribution> target,
                                              String stem, String serialized) {
        GranularContribution parsed = GranularContribution.parse(serialized);
        if (parsed != null && !parsed.isEmpty()) {
            target.put(stem, parsed);
        }
    }

    /**
     * Loads the sidecar previously persisted for {@code moduleId}, or {@code null} when there is
     * none (or it is unreadable). Lets a compilation see what it recorded last time <em>before</em>
     * it overwrites it — the only way to notice that a round is replacing a module's guardrails
     * wholesale rather than updating them.
     */
    public static @Nullable ModuleSidecar loadFor(Path root, String moduleId) {
        Path path = root.resolve(SIDECAR_PREFIX + moduleId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        ModuleSidecar loaded = load(path);
        return loaded == FUTURE_VERSION || loaded == UNREADABLE ? null : loaded;
    }

    /** True when {@link #TRAILER} is the last non-blank line, i.e. the writer got to the end. */
    private static boolean isWhole(List<String> lines) {
        String last = "";
        for (int i = lines.size() - 1; i >= 0 && last.isEmpty(); i--) {
            last = lines.get(i).strip();
        }
        return TRAILER.equals(last);
    }

    static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    /** Adds each non-blank line of a decoded list value to {@code into}, in order. */
    static void addNonBlankLines(String list, Collection<String> into) {
        // -1 keeps trailing empty fields rather than relying on split's default of dropping them;
        // the isBlank guard is what discards them, visibly.
        for (String entry : list.split("\n", -1)) {
            if (!entry.isBlank()) {
                into.add(entry);
            }
        }
    }

    /**
     * Reads all valid sidecar files from {@code root}, sorted by filename for deterministic
     * ordering. Automatically deletes sidecars whose module path no longer resolves to an existing
     * directory — this handles modules removed from the project.
     *
     * <p><strong>Parallel builds:</strong> if two modules compile concurrently (e.g. Gradle
     * {@code --parallel}), a sibling's sidecar may be absent or mid-write when this method runs.
     * The worst case is a single build cycle where one module's content is missing from the merged
     * output; the next incremental build picks it up because the sidecar stamp will have changed.
     *
     * <p>Reports its skips and prunes on whatever logger the round already configured for
     * {@code root}, resolved rather than passed. The caller that needs that is
     * {@code AIGuardrailProcessor.generateFiles()}, which is {@code @AILocked} because its step
     * order is load-bearing: threading a logger through it would have edited locked code to add a
     * diagnostic, which is the trade the lock exists to refuse. Callers that hold a logger should
     * pass it to {@link #readAll(Path, Logger)} instead - explicit beats ambient wherever the
     * choice exists, and a test with no configured root gets {@code null} here and stays silent.
     */
    public static List<ModuleSidecar> readAll(Path root) {
        return readAll(root, true, VibeTagsLogger.currentFor(root));
    }

    /**
     * {@link #readAll(Path)}, saying on {@code log} why any sidecar was skipped or pruned.
     *
     * <p>A module vanishing from the merged output is the failure this class exists to prevent, and
     * until now it left no trace at all: {@code ModuleSidecar} held no logger, so a deleted or
     * skipped sidecar was invisible in {@code vibetags.log} (issue #555). Every drop now names its
     * reason, which docs/LOGGING.md requires of any {@code .skip}.
     */
    public static List<ModuleSidecar> readAll(Path root, @Nullable Logger log) {
        return readAll(root, true, log);
    }

    /**
     * {@link #readAll(Path)} without its side effect: a malformed or stale sidecar is left out of
     * the result but stays on disk. For readers that must not write — check mode above all, whose
     * promise is that it touches nothing VibeTags manages, and whose pruning of a departed
     * module's sidecar threw away the only record of the rule files that module wrote, so the
     * next real build could no longer remove them (see {@link #staleGranularStems}).
     *
     * <p>Resolves its logger the way {@link #readAll(Path)} does, and for the same reason.
     */
    public static List<ModuleSidecar> peekAll(Path root) {
        return readAll(root, false, VibeTagsLogger.currentFor(root));
    }

    /** {@link #peekAll(Path)}, saying on {@code log} why any sidecar was left out. */
    public static List<ModuleSidecar> peekAll(Path root, @Nullable Logger log) {
        return readAll(root, false, log);
    }

    private static List<ModuleSidecar> readAll(Path root, boolean prune, @Nullable Logger log) {
        List<ModuleSidecar> result = readAll(root, prune, log, ModuleSidecar::load);
        applyRootIndexModeTo(root, result);
        applyTestingOptInTo(root, result);
        return result;
    }

    /** How {@link #readAll} turns one file into a sidecar: in full, or as a {@link #loadSummary}. */
    @FunctionalInterface
    private interface Loader {
        @Nullable ModuleSidecar load(Path path);
    }

    /**
     * The sidecars at {@code root} as {@code loader} reads them, less every one a merge would leave
     * out: unreadable, malformed, future-version, stale module path, superseded region. Shared by
     * the full read and the summary read so both leave out the same files.
     */
    private static List<ModuleSidecar> readAll(Path root, boolean prune, @Nullable Logger log, Loader loader) {
        return readAll(root, prune, log, loader, null);
    }

    /**
     * {@link #peekAll(Path, Logger)} as generation reads it right after saving {@code saved}: the
     * sidecars on disk with {@code saved} in place of its own file, stamped with the time that
     * save would leave on it, and only then superseded regions dropped. Deletes nothing.
     *
     * <p>Check mode's stand-in for generation's save-then-{@code readAll}. Substituting after
     * {@code peekAll} pruned the files on disk judged the regions without this module's fresh
     * sidecar, and a region the save would have retired, this module's own included, was merged
     * anyway: check mode reported drift on a tree generation leaves untouched (#766).
     */
    public static List<ModuleSidecar> peekAllAfterSaving(Path root, ModuleSidecar saved, @Nullable Logger log) {
        List<ModuleSidecar> result = readAll(root, false, log, ModuleSidecar::load, saved);
        applyRootIndexModeTo(root, result);
        applyTestingOptInTo(root, result);
        return result;
    }

    private static List<ModuleSidecar> readAll(Path root, boolean prune, @Nullable Logger log, Loader loader,
                                               @Nullable ModuleSidecar saved) {
        if (!Files.isDirectory(root)) return new ArrayList<>();
        return new RootRead(root, prune, log).read(loader, saved);
    }

    /**
     * A sidecar this merge can use, with the file it was read from and that file's mtime in millis
     * ({@link Long#MAX_VALUE} when it cannot be read). The file is kept so that a sidecar retired
     * after every other one is known can still be named in the log and deleted.
     */
    private record Loaded(ModuleSidecar sidecar, Path file, long writtenAt) { }

    /**
     * One read of every sidecar under a root: load each file and leave out what this merge cannot
     * use, then retire the leftovers that only show once the rest is known. Every sidecar left out
     * says why in the log, and is deleted only when the read prunes.
     */
    private static final class RootRead {

        private final Path root;
        /** Whether a sidecar dropped for good is also deleted. Check mode and the questions asked before a round do not. */
        private final boolean prune;
        private final @Nullable Logger log;
        /** What is still in the merge, in filename order. */
        private final List<Loaded> loaded = new ArrayList<>();

        RootRead(Path root, boolean prune, @Nullable Logger log) {
            this.root = root;
            this.prune = prune;
            this.log = log;
        }

        List<ModuleSidecar> read(Loader loader, @Nullable ModuleSidecar saved) {
            // listPaths is best-effort: a root we cannot read contributes no sidecars, which is the
            // same outcome as a root with none. Failing here would fail a compile over a directory
            // the build does not need.
            for (Path file : listPaths(root)) {
                ModuleSidecar sidecar = usable(file, loader.load(file));
                if (sidecar != null) {
                    loaded.add(new Loaded(sidecar, file, lastModified(file)));
                }
            }
            if (saved != null) {
                substitute(saved);
            }
            dropSupersededRegions();
            dropMisreadSourceSets();
            List<ModuleSidecar> sidecars = new ArrayList<>(loaded.size());
            for (Loaded entry : loaded) {
                sidecars.add(entry.sidecar());
            }
            return sidecars;
        }

        /** What {@code file} was loaded as when this merge can use it; otherwise {@code null}, having said why not. */
        private @Nullable ModuleSidecar usable(Path file, @Nullable ModuleSidecar sidecar) {
            if (sidecar == UNREADABLE) {
                // Locked, vanished, or being renamed over by the module that owns it —
                // a sibling save in a parallel reactor does exactly that on Windows.
                // Skip this round (readAll already tolerates a missing sibling) and,
                // above all, do not delete a file we never managed to look at.
                logSkipped(log, file, "unreadable");
                return null;
            }
            if (sidecar == null) {
                drop(file, formatReason(file));
                return null;
            }
            if (sidecar == FUTURE_VERSION) {
                // Written by a newer processor in a mixed-version build: skip it (its
                // module's content is missing from OUR merge, the newer module merges
                // everything correctly) but never delete a sibling's valid sidecar.
                logSkipped(log, file, "future-version");
                return null;
            }
            // Stale check: if the module path (relative to root) no longer exists, prune.
            if (!sidecar.modulePath.isEmpty() && !"_root_".equals(sidecar.modulePath)) {
                ModuleDir dir = moduleDir(root, sidecar.modulePath);
                if (dir != ModuleDir.EXISTS) {
                    drop(file, dir == ModuleDir.UNREPRESENTABLE ? "invalid-module-path" : "module-gone");
                    return null;
                }
            }
            return sidecar;
        }

        /** Says why {@code file} is out of the merge for good, and deletes it when this read prunes. */
        private void drop(Path file, String reason) {
            logDropped(log, prune, file, reason);
            if (prune) {
                tryDelete(file);
            }
        }

        /** Takes every loaded sidecar {@code retired} matches out of the merge, last file first. */
        private void leaveOut(Predicate<Loaded> retired, String reason) {
            for (int i = loaded.size() - 1; i >= 0; i--) {
                if (retired.test(loaded.get(i))) {
                    drop(loaded.get(i).file(), reason);
                    loaded.remove(i);
                }
            }
        }

        /**
         * Puts {@code saved} where {@link #save} would put it: in place of its own entry, or at its
         * place in filename order when it has none. The time is what the save leaves: unchanged
         * content is not rewritten (#556), so the file keeps its mtime; anything else is written
         * now.
         */
        private void substitute(ModuleSidecar saved) {
            Path target = root.resolve(SIDECAR_PREFIX + saved.moduleId);
            long stamp = sameAsOnDisk(target, saved.serialized()) ? lastModified(target) : System.currentTimeMillis();
            for (int i = 0; i < loaded.size(); i++) {
                if (loaded.get(i).sidecar().moduleId.equals(saved.moduleId)) {
                    loaded.set(i, new Loaded(saved, loaded.get(i).file(), stamp));
                    return;
                }
            }
            int at = 0;
            while (at < loaded.size() && loaded.get(at).sidecar().moduleId.compareTo(saved.moduleId) < 0) {
                at++;
            }
            loaded.add(at, new Loaded(saved, target, stamp));
        }

        /**
         * Leaves out every region {@link SupersededRegions} names as a leftover identity for
         * sources another region now speaks for.
         */
        private void dropSupersededRegions() {
            if (loaded.size() < MULTI_MODULE_THRESHOLD) {
                return;
            }
            Map<String, SupersededRegions.Region> regions = new LinkedHashMap<>();
            for (Loaded entry : loaded) {
                ModuleSidecar s = entry.sidecar();
                // A region spans one sidecar per source set: joined, it keeps the first one's
                // path, claims what any of them claims, and is as fresh as its freshest.
                regions.merge(s.regionId,
                    new SupersededRegions.Region(s.regionId, normalizeModulePath(s.modulePath),
                        s.elementIds, entry.writtenAt()),
                    SupersededRegions.Region::joinedWith);
            }
            Set<String> superseded = SupersededRegions.among(regions.values());
            leaveOut(entry -> superseded.contains(entry.sidecar().regionId), "superseded");
        }

        /**
         * Leaves out a source-set sidecar written under a misread name.
         *
         * <p>Before #1068 a source root that is itself {@code src/}, packages directly below it, had its
         * source set read from the first package segment: {@code shared/src/com/...} was filed as
         * {@code shared__com}, {@code shared/src/test/...} as {@code shared__test}. A build after the fix
         * files the same sources as {@code shared}, and the old sidecar is not retired by any other rule:
         * its module directory exists, its region is the module's own. Left in place it renders its
         * guardrails a second time, and, because an emptied source set keeps its last contribution, it
         * keeps an annotation that was deleted from the source in every generated file for good.
         *
         * <p>Such a sidecar is recognised by its sibling, not by its name: a non-main sidecar is retired
         * when another sidecar of the same module and region, written no earlier, records a source root
         * whose first {@code src} segment below the module directory is its last. A source root that
         * is a flat {@code src/} cannot hold a {@code src/<set>/java} tree of a real second source set,
         * so the pair is only ever the misread one. Freshness decides the direction: a sibling written
         * earlier, or whose time cannot be read, retires nothing.
         *
         * <p>A root "whose first {@code src} segment is its last" is not only {@code <module>/src}: a
         * main round that also compiles {@code gen/src} records one too, and the module's real
         * {@code __test} sidecar was then retired on every compile-only build (#1159). So a candidate
         * that records a {@code src/<its own set>/...} root of its own is never misread: the misread
         * sidecars record the flat root itself, or no roots at all (they predate {@code ~roots}).
         * Nor is one that records a root its sibling does not: a misread sidecar compiled the same
         * sources, so its roots are among the sibling's, while a set compiled from {@code it/src}
         * alone records no {@code src/it/...} root and was retired (#1214).
         */
        private void dropMisreadSourceSets() {
            List<Loaded> misread = new ArrayList<>();
            for (Loaded candidate : loaded) {
                String set = PartialRoundDetector.sourceSetOf(candidate.sidecar());
                if (!ModuleIdentity.MAIN.equals(set)
                        && !recordsItsOwnSetRoot(root, candidate.sidecar(), set)
                        && hasANoOlderSiblingOnAFlatSrcRoot(candidate)) {
                    misread.add(candidate);
                }
            }
            leaveOut(misread::contains, "misread-source-set");
        }

        private boolean hasANoOlderSiblingOnAFlatSrcRoot(Loaded candidate) {
            for (Loaded sibling : loaded) {
                if (!sibling.equals(candidate)
                        && sibling.sidecar().regionId.equals(candidate.sidecar().regionId)
                        && sibling.writtenAt() != Long.MAX_VALUE
                        && sibling.writtenAt() >= candidate.writtenAt()
                        && recordsAFlatSrcRoot(root, sibling.sidecar())
                        && recordsEveryRootOf(root, sibling.sidecar(), candidate.sidecar())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * True when {@code sibling} records every source root {@code candidate} records, which holds
     * vacuously for a candidate that records none. A misread sidecar compiled the same sources as
     * its sibling, so its roots are among the sibling's; a source set compiled from a root of its
     * own, such as {@code it/src}, records one the sibling does not and is never the misread one
     * (#1214).
     */
    private static boolean recordsEveryRootOf(Path root, ModuleSidecar sibling, ModuleSidecar candidate) {
        return PartialRoundDetector.recordedSourceRoots(root, sibling)
            .containsAll(PartialRoundDetector.recordedSourceRoots(root, candidate));
    }

    /**
     * True when {@code sidecar} records a source root below its module that passes through
     * {@code src/<set>}, the conventional tree of the source set the sidecar is named after.
     */
    private static boolean recordsItsOwnSetRoot(Path root, ModuleSidecar sidecar, String set) {
        Path dir = PartialRoundDetector.moduleDirOf(root, sidecar);
        if (dir == null) return false;
        for (Path sourceRoot : PartialRoundDetector.recordedSourceRoots(root, sidecar)) {
            if (sourceRoot.startsWith(dir) && hasSegmentPair(dir.relativize(sourceRoot), "src", set)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code first} is immediately followed by {@code second} somewhere in {@code path}. */
    private static boolean hasSegmentPair(Path path, String first, String second) {
        for (int k = 0; k + 1 < path.getNameCount(); k++) {
            if (first.equals(path.getName(k).toString()) && second.equals(path.getName(k + 1).toString())) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code sidecar} records a source root below its module whose first {@code src} segment is its last. */
    private static boolean recordsAFlatSrcRoot(Path root, ModuleSidecar sidecar) {
        Path dir = PartialRoundDetector.moduleDirOf(root, sidecar);
        if (dir == null) return false;
        for (Path sourceRoot : PartialRoundDetector.recordedSourceRoots(root, sidecar)) {
            if (!sourceRoot.startsWith(dir) || sourceRoot.equals(dir)) continue;
            Path rel = dir.relativize(sourceRoot);
            for (int k = 0; k < rel.getNameCount(); k++) {
                if ("src".equals(rel.getName(k).toString())) {
                    if (k == rel.getNameCount() - 1) return true;
                    break;
                }
            }
        }
        return false;
    }

    /** Sidecar mtime in millis, or {@link Long#MAX_VALUE} when it cannot be read. */
    private static long lastModified(Path sidecar) {
        try {
            return Files.getLastModifiedTime(sidecar).toMillis();
        } catch (IOException | RuntimeException unreadable) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * A module path in comparable form: forward slashes, no trailing separator, and the historical
     * {@code "_root_"} spelling of "this is the root" folded onto the empty string.
     */
    private static String normalizeModulePath(String modulePath) {
        String p = PathStrings.forwardSlashes(modulePath);
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return "_root_".equals(p) ? "" : p;
    }

    // -----------------------------------------------------------------------
    // Lean indexed-root (multi-module): link to per-module rules instead of embedding
    // -----------------------------------------------------------------------

    /** Aggregate services that have a glob-scoped granular sibling and can therefore be linked. */
    public static final List<String> INDEXABLE_AGGREGATES =
        java.util.Arrays.stream(GranularPairing.values()).map(GranularPairing::aggregateKey).toList();

    /**
     * When the reactor root opted into the lean index ({@code .vibetags-root-index} present), flags
     * every sidecar and precomputes, for each non-root module, the pointer text that replaces its
     * embedded body in the root aggregate. Runs here (not in {@code mergeFor}) because it needs the
     * filesystem — the module's own opt-in files determine where its guardrails live — keeping
     * {@code mergeFor} disk-free and its @AIContract signature untouched.
     */
    public static void applyRootIndexModeTo(Path root, List<ModuleSidecar> sidecars) {
        if (!Files.exists(root.resolve(ServiceRegistry.ROOT_INDEX_FILE))) return;
        for (ModuleSidecar s : sidecars) {
            s.rootIndexMode = true;
            s.linkFromRoot(root);
        }
    }

    /**
     * Records, for each indexable aggregate this module contributes to, the pointer the lean root
     * writes in place of its body. Nothing for the root module, whose guardrails are not duplicated
     * elsewhere and stay inline, nor for a module whose directory is gone.
     */
    private void linkFromRoot(Path root) {
        if (modulePath.isEmpty() || "_root_".equals(moduleId)) return;
        Path moduleDir = root.resolve(modulePath);
        if (!Files.isDirectory(moduleDir)) return;
        // The unrouted body counts too: it is what the merge reads once TESTING.md is withdrawn
        // (reactorBody), and a test round whose tests carry no safety annotation stores no other.
        // Without a pointer that body was embedded whole in a root opted into being lean (#1156).
        List<String> contributed = INDEXABLE_AGGREGATES.stream()
            .filter(agg -> bodies.containsKey(agg) || unroutedBodies.containsKey(agg))
            .toList();
        // Only the eight keys buildIndexPointer consults, and only for aggregates this module
        // actually contributes to. resolveActiveServices would stat every opt-in path instead:
        // measured at 85 per call, 4 calls per build of the indexed example, 340 of that build's
        // 850 opt-in stats, to read 8 answers. Its one post-filter drops codex, which is not among
        // the eight, so narrowing changes no verdict.
        Map<String, Path> moduleFiles = ServiceRegistry.buildServiceFileMap(moduleDir);
        Set<String> moduleActive = new LinkedHashSet<>();
        for (String agg : contributed) {
            addIfOptedIn(moduleActive, moduleFiles, agg);
            addIfOptedIn(moduleActive, moduleFiles, aggregateGranularKey(agg));
        }
        for (String agg : contributed) {
            String pointer = buildIndexPointer(agg, modulePath, moduleActive);
            if (pointer != null) indexPointers.put(agg, pointer);
        }
    }

    /**
     * True when at least one sidecar carries the reactor-root lean-index opt-in.
     *
     * <p>Public because the merge path is otherwise gated on there being more than one sidecar, and
     * a reactor can legitimately have exactly one: a module contributes a sidecar only when it has
     * annotations, so a project where a single module holds all of them produces one. Without this
     * the opt-in would be read, reported as an active service, and then silently skipped — see
     * {@code AIGuardrailProcessor.generateFiles}.
     */
    public static boolean isRootIndexMode(List<ModuleSidecar> sidecars) {
        for (ModuleSidecar s : sidecars) {
            if (s.rootIndexMode) return true;
        }
        return false;
    }

    /**
     * Flags every sidecar when {@code TESTING.md} is not opted in at {@code root}, so the merge
     * reads a routed round's {@code ~tfull~} body in place of its ordinary one.
     *
     * <p>Runs here for the reason {@link #applyRootIndexModeTo} does: it needs the filesystem, and
     * {@code mergeFor} stays disk-free with its contract signature untouched. The opt-in is asked
     * of {@link ServiceRegistry#isOptedIn}, not of {@code Files.exists}: a directory named
     * {@code TESTING.md} is not the opt-in anywhere else either (issue #642).
     *
     * <p>The flag marks absence, not presence, so its default is the harmless one. A sidecar built
     * in memory and never passed through {@code readAll} keeps the body it was given.
     */
    static void applyTestingOptInTo(Path root, List<ModuleSidecar> sidecars) {
        Path testing = ServiceRegistry.buildServiceFileMap(root).get("testing");
        if (testing != null && ServiceRegistry.isOptedIn("testing", testing)) return;
        for (ModuleSidecar s : sidecars) {
            s.testingWithdrawn = true;
        }
    }

    /**
     * True when some sidecar at {@code root} records annotated elements, i.e. a source set of this
     * project had guardrails the last time it compiled.
     *
     * <p>Asked before the first round (#781). javac never calls a processor whose supported
     * annotation types match nothing, so a source set whose last annotation was just removed would
     * never be shown to VibeTags, and its sidecar would keep contributing the removed guardrail for
     * good. Which source set is compiling is not known until a round runs, so this cannot be asked
     * of one sidecar; it is asked of all of them. A project with no sidecar, which is every project
     * with no annotations, is unaffected.
     */
    public static boolean anyRecordsElements(Path root) {
        return onRecord(root).recordsElements();
    }

    /** {@link #anyRecordsElements(Path)} over sidecars the caller has already read. */
    public static boolean anyRecordsElements(List<ModuleSidecar> sidecars) {
        for (ModuleSidecar s : sidecars) {
            if (!s.elementIds.isEmpty()) return true;
        }
        return false;
    }

    /**
     * True when the merge would read at least one sidecar's unrouted fallback: {@code TESTING.md}
     * is gone and a routed round's full body is still on record.
     *
     * <p>Admits a lone sidecar to the merge path (#782). A module whose main sources carry no
     * annotation has exactly one sidecar, the test round's, and a merge gated on count alone never
     * consults the fallback that exists for this case.
     */
    public static boolean isTestingFallbackInForce(List<ModuleSidecar> sidecars) {
        for (ModuleSidecar s : sidecars) {
            if (!s.testingWithdrawn) continue;
            for (String unrouted : s.unroutedBodies.values()) {
                if (!unrouted.isBlank()) return true;
            }
        }
        return false;
    }

    /**
     * True when a sidecar at {@code root} still holds a routed round's unrouted body while
     * {@code TESTING.md} is no longer opted in, so guardrails sit in a sidecar and in no file.
     *
     * <p>Asked before the first round, to decide whether this compilation must run at all (#782):
     * javac never calls a processor whose supported annotation types match nothing, so a main-only
     * build of unannotated sources would leave the fallback unread until the tests are compiled
     * again. Peeks rather than reads, since a question asked before any round must not prune.
     */
    public static boolean holdsWithdrawnTestingFallback(Path root) {
        return onRecord(root).withdrawnTestingFallback();
    }

    /** {@link #holdsWithdrawnTestingFallback(Path)} over sidecars the caller has already read. */
    public static boolean holdsWithdrawnTestingFallback(Path root, List<ModuleSidecar> sidecars) {
        return !testingOptedIn(root) && anyUnroutedBody(sidecars);
    }

    private static boolean testingOptedIn(Path root) {
        Path testing = ServiceRegistry.buildServiceFileMap(root).get("testing");
        return testing != null && ServiceRegistry.isOptedIn("testing", testing);
    }

    private static boolean anyUnroutedBody(List<ModuleSidecar> sidecars) {
        for (ModuleSidecar s : sidecars) {
            for (String unrouted : s.unroutedBodies.values()) {
                if (!unrouted.isBlank()) return true;
            }
        }
        return false;
    }

    /**
     * What {@code init()} needs from the sidecars on record before any round: whether one records
     * annotated elements ({@link #anyRecordsElements(Path)}, #781), and whether one holds a routed
     * test round's unrouted body while {@code TESTING.md} is gone
     * ({@link #holdsWithdrawnTestingFallback(Path)}, #782).
     */
    public record OnRecord(boolean recordsElements, boolean withdrawnTestingFallback) { }

    /**
     * {@link OnRecord} for {@code root}, reached without keeping or decoding a rendered body (#858).
     *
     * <p>Both questions used to be asked of {@link #peekAll}, which decodes every rendered body of
     * every module, and {@code init()} asks them on every compilation: after #834 that parse was
     * most of what a no-op rebuild still allocated. Neither answer is in the bodies. Each sidecar
     * is streamed past by {@link #loadSummary} instead, and the summaries go through the same
     * filtering a peek applies, so a stale module path or a superseded region is left out here
     * exactly as there. Like {@code peekAll} it never prunes: the question is asked before any
     * round. {@code ModuleSidecarOnRecordAgreementTest} holds the answers to the full parse's, and
     * {@code ModuleSidecarOnRecordCostTest} holds the cost flat in the size of the bodies.
     */
    public static OnRecord onRecord(Path root) {
        boolean testingOptedIn = testingOptedIn(root);
        List<ModuleSidecar> summaries = readAll(root, false, null, p -> loadSummary(p, !testingOptedIn));
        return new OnRecord(anyRecordsElements(summaries), !testingOptedIn && anyUnroutedBody(summaries));
    }

    /**
     * The body this sidecar contributes to the shared file of {@code serviceKey}: the ordinary one,
     * unless the round that wrote it was routed and {@code TESTING.md} has since gone, in which case
     * the ordinary body is only the safety half and the unrouted body is the whole of it.
     */
    @Nullable String reactorBody(String serviceKey) {
        if (testingWithdrawn) {
            String unrouted = unroutedBodies.get(serviceKey);
            if (unrouted != null && !unrouted.isBlank()) {
                return unrouted;
            }
        }
        return bodies.get(serviceKey);
    }

    /** The lean-index pointer {@link #applyRootIndexModeTo} left for {@code serviceKey}, or {@code null}. */
    @Nullable String indexPointer(String serviceKey) {
        return indexPointers.get(serviceKey);
    }

    /** The safety-tier digest this sidecar recorded for {@code serviceKey}, or {@code null}. */
    @Nullable String indexDigest(String serviceKey) {
        return indexDigests.get(serviceKey);
    }

    /**
     * The ids of the sidecars whose body for {@code serviceKey} the merge is reading from the
     * unrouted fallback, in sidecar order. For the {@code merge.testing.fallback} log event: the
     * merged file is well formed either way, so nothing else records that the substitution happened.
     */
    public static List<String> testingFallbackModules(String serviceKey, List<ModuleSidecar> sidecars) {
        List<String> modules = new ArrayList<>();
        for (ModuleSidecar s : sidecars) {
            String unrouted = s.unroutedBodies.get(serviceKey);
            if (s.testingWithdrawn && unrouted != null && !unrouted.isBlank()) {
                modules.add(s.moduleId);
            }
        }
        return modules;
    }

    /** Glob-scoped granular directory (no trailing slash) for an aggregate service, else {@code null}. */
    private static @Nullable String aggregateScopedDir(String service) {
        GranularPairing pairing = GranularPairing.forAggregate(service);
        return pairing == null ? null : pairing.scopedDir();
    }

    /** Granular service key governing an aggregate service (e.g. {@code claude} → {@code claude_granular}). */
    private static @Nullable String aggregateGranularKey(String service) {
        GranularPairing pairing = GranularPairing.forAggregate(service);
        return pairing == null ? null : pairing.granularKey();
    }

    /** Adds {@code key} to {@code into} when its file or directory is present under the module. */
    private static void addIfOptedIn(Set<String> into, Map<String, Path> moduleFiles, @Nullable String key) {
        if (key == null) return;
        Path file = moduleFiles.get(key);
        if (file != null && ServiceRegistry.isOptedIn(key, file)) {
            into.add(key);
        }
    }

    /** The always-loaded aggregate file name for an aggregate service, else {@code null}. */
    private static @Nullable String aggregateFileName(String service) {
        GranularPairing pairing = GranularPairing.forAggregate(service);
        return pairing == null ? null : pairing.aggregateFile();
    }

    /**
     * Builds the pointer text that replaces a module's embedded aggregate body, or {@code null} when
     * the module has no per-module output for this service (in which case the body stays embedded so
     * no guardrails are lost). {@code moduleActive} is the module directory's own file-existence
     * opt-in set — the pointer names only the files the module actually generates.
     */
    private static @Nullable String buildIndexPointer(String service, String modulePath,
                                                      Set<String> moduleActive) {
        String scopedDir = aggregateScopedDir(service);
        if (scopedDir == null) return null;
        boolean hasGranular = moduleActive.contains(aggregateGranularKey(service));
        GranularPairing pairing = GranularPairing.forAggregate(service);
        boolean loadsOnOpen = pairing == null || pairing.loadsScopedFilesOnOpen();
        boolean hasAggregate = moduleActive.contains(service);
        if (!hasGranular && !hasAggregate) return null; // module keeps nothing of its own → embed as before

        String mp = PathStrings.forwardSlashes(modulePath);
        StringBuilder p = new StringBuilder();
        p.append("Guardrails for module `").append(mp).append("` are maintained in that module's own files");
        if (hasGranular) {
            p.append(", in the scoped rules under `").append(mp).append('/').append(scopedDir)
             .append("/` (")
             .append(loadsOnOpen
                 ? "loaded automatically when you open a matching source file"
                 : "not loaded by themselves: open the matching rule file before you edit a source file")
             .append(')');
        }
        if (hasAggregate) {
            p.append(hasGranular ? " and `" : ", in `").append(mp).append('/').append(aggregateFileName(service)).append('`');
        }
        p.append(". Consult those for this module's full guardrails.");
        return p.toString();
    }

    /**
     * The sidecars present on disk that this processor could not read, by file name.
     *
     * <p>Two shapes reach here, and both mean the same thing to the caller: this round is about to
     * render an aggregate that is missing whoever owns these files. A {@code future-version}
     * sidecar belongs to a module compiled by a newer processor - a mixed-version reactor, which is
     * a misconfiguration but a silent one (issue #592). An {@code unreadable} one is a file locked,
     * vanishing or mid-rename under a parallel build, which is ordinary on Windows and clears on
     * the next round.
     *
     * <p>Neither is deleted, and neither can be merged. What was missing was anyone saying so:
     * both are logged at DEBUG, which nobody has on, so the module simply disappeared from the
     * generated files and the developer found it in a diff. This is what lets the round state it.
     *
     * <p>Deliberately re-reads rather than being folded into {@link #readAll}: that method is
     * called from a step-ordered sequence this must not perturb. It re-reads cheaply, though, and
     * that part took two goes. Reaching the verdict through {@link #load} parsed every sidecar a
     * second time, and a sidecar is not a directory entry but its module's whole collected model:
     * measured on a fixture of eight, that second parse allocated 7.3x the bytes on disk, which a
     * release-to-release sweep saw as a 5.9 % step in the processor's allocation overhead
     * (issue #833). {@code SidecarScans.scanUnreadable} answers the same question by streaming, so the cost is
     * one buffer per file rather than a share of what the file weighs, and
     * {@code ModuleSidecarUnreadableScanCostTest} keeps it that way.
     *
     * @return file names, sorted, or empty when every sidecar on disk was readable
     */
    public static List<String> unreadableSidecarNames(Path root) {
        return SidecarScans.unreadableNames(root);
    }

    /**
     * Lists sidecar file paths under {@code root} without parsing them.
     * Used to compute a lightweight stamp for the fingerprint short-circuit.
     */
    public static List<Path> listPaths(Path root) {
        if (!Files.isDirectory(root)) return new ArrayList<>();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> {
                      // Path.getFileName() returns null only for root paths — guard for correctness.
                      Path fn = p.getFileName();
                      if (fn == null) return false;
                      String n = fn.toString();
                      return n.startsWith(SIDECAR_PREFIX) && !n.endsWith(".tmp");
                  })
                  .sorted(Comparator.comparing(p -> {
                      Path fn = p.getFileName();
                      return fn != null ? fn.toString() : "";
                  }))
                  .forEach(result::add);
        } catch (IOException ignored) {

            // Same best-effort listing contract as above: unreadable means empty, never fatal.

        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Multi-module merge
    // -----------------------------------------------------------------------

    /**
     * Merges per-service bodies from all sidecars for {@code serviceKey}.
     *
     * <ul>
     *   <li>Zero contributions → returns {@code ""}.</li>
     *   <li>Single contribution → returns the body as-is (no sub-markers, identical to the
     *       existing single-module output).</li>
     *   <li>Multiple contributions → wraps each in module sub-markers so the AI platform can see
     *       each module's guardrails and humans can trace which module set which rule.</li>
     * </ul>
     *
     * <p>Sidecars are grouped by <em>region</em> first, so a module's several source sets share one
     * sub-marker pair instead of masquerading as separate modules (issue #330).
     *
     * @param serviceKey  e.g. {@code "cursor"}, {@code "claude"}
     * @param sidecars    all known module sidecars (current + siblings)
     * @param htmlMarkers {@code true} for {@code <!-- -->} style, {@code false} for {@code #} style
     */
    @AIContract(reason = "Sub-marker format constants (SUB_MARKER_*_FORMAT) are embedded in generated CLAUDE.md and .cursorrules; changing them silently corrupts multi-module merged output on the next compile")
    public static String mergeFor(String serviceKey, List<ModuleSidecar> sidecars, boolean htmlMarkers) {
        return SidecarBodyMerge.reactorFile(serviceKey, sidecars, SubMarkers.of(htmlMarkers));
    }

    /**
     * Every region's body for {@code serviceKey}, as (region id → body) in output order.
     *
     * <p>The input a {@code WholeFileMerge} works from. Grouped by region for the same reason
     * {@link #mergeFor} groups: a module compiled twice (main and test source sets) is one
     * contributor, not two, and its source sets are joined before the format-aware merge sees them
     * (issue #330). Regions with no body for this service are omitted rather than contributing an
     * empty document.
     *
     * @param serviceKey e.g. {@code "pr_agent"}
     * @param sidecars   all known module sidecars (current + siblings)
     */
    public static List<Map.Entry<String, String>> contributionsFor(String serviceKey,
                                                                   List<ModuleSidecar> sidecars) {
        return SidecarBodyMerge.contributionsFor(serviceKey, sidecars);
    }

    /**
     * Merges the <em>module's own</em> nested output for {@code serviceKey} across the source sets
     * of one region, main first (sidecars arrive sorted by filename, and the main source set's id
     * carries no suffix). Returns {@code ""} when no sidecar in the region has a body — the caller
     * then falls back to this compilation's freshly built content.
     */
    public static String mergeModuleBodies(String serviceKey, List<ModuleSidecar> sidecars, String regionId) {
        return SidecarBodyMerge.mergeModuleBodies(serviceKey, sidecars, regionId);
    }

    /**
     * Granular rule stems recorded by every sidecar except {@code excludeModuleId} (this
     * compilation's own, which the caller already knows). Used as extra cleanup exclusions so a
     * round never deletes rule files belonging to a module or source set it could not see.
     *
     * @param sameRegionOnly when non-null, only sidecars carrying that region id are considered —
     *                       the right scope for a module's own {@code .claude/rules} directory
     */
    public static Set<String> granularStemsFrom(List<ModuleSidecar> sidecars,
                                                @Nullable String excludeModuleId,
                                                @Nullable String sameRegionOnly) {
        Set<String> stems = new LinkedHashSet<>();
        for (ModuleSidecar s : sidecars) {
            if (s.moduleId.equals(excludeModuleId)) continue;
            if (sameRegionOnly != null && !s.regionId.equals(sameRegionOnly)) continue;
            stems.addAll(s.granularStems);
        }
        return stems;
    }

    /**
     * Every module's contribution to each <em>shared</em> granular rule file, keyed by stem.
     *
     * <p>The granular counterpart of {@link #mergeFor}. A rule file is not owned by one module: a
     * role declared in a reactor-root {@code .vibetags-roles} routes classes from several modules
     * into one file, and each of them resolves the same path, so before this every module's compile
     * replaced the file with its own classes alone
     * (<a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>). Several regions
     * are wrapped in {@code VIBETAGS-MODULE} sub-markers, exactly as the aggregate files are;
     * granular files are markdown on every platform, so the HTML marker form always applies. How
     * regions, source sets and case-colliding stems fold is {@code GranularMerge}'s to say.
     */
    public static Map<String, GranularContribution> mergeGranular(List<ModuleSidecar> sidecars) {
        return GranularMerge.of(sidecars, s -> s.granularUnits, true, null);
    }

    /**
     * As above for a module's own nested granular output, across the source sets of one region.
     *
     * <p>No sub-markers and no cross-module merge: this file lives under the module directory and
     * holds that module's guardrails only. What it does need is the {@code test-compile} round's
     * contribution beside the {@code compile} round's, for the same reason
     * {@link #mergeModuleBodies} does — otherwise the second round replaces the first's role file.
     */
    public static Map<String, GranularContribution> mergeModuleGranular(List<ModuleSidecar> sidecars,
                                                                        String regionId) {
        return GranularMerge.of(sidecars, s -> s.moduleGranularUnits, false, regionId);
    }

    /**
     * Number of distinct modules represented by {@code sidecars}. Counts <em>regions</em>, not
     * files: a single-module project whose test sources are annotated too has two sidecars but is
     * still one module, and must keep its historical sub-marker-free output.
     */
    public static int regionCount(List<ModuleSidecar> sidecars) {
        Set<String> regions = new LinkedHashSet<>();
        for (ModuleSidecar s : sidecars) {
            regions.add(s.regionId);
        }
        return regions.size();
    }

    // -----------------------------------------------------------------------
    // Module ID helpers
    // -----------------------------------------------------------------------

    /**
     * Computes a filename-safe module identifier from the compilation root's path relative to
     * the VibeTags output root. Used as the sidecar filename suffix.
     *
     * <p>Examples:
     * <ul>
     *   <li>roots equal → {@code "_root_"}</li>
     *   <li>compilationRoot = root/module-graph → {@code "module-graph"}</li>
     *   <li>compilationRoot = root/a/b → {@code "a_b"} (path separators → underscore)</li>
     * </ul>
     */
    public static String computeModuleId(Path compilationRoot, Path vibetagsRoot) {
        try {
            Path rel = vibetagsRoot.relativize(compilationRoot);
            String relStr = rel.toString();
            if (relStr.isEmpty() || ".".equals(relStr)) return "_root_";
            // The compilation root is not *under* the VibeTags root, so the relative path escapes
            // upward. Two things are needed at once. The id has to be bounded, because it becomes
            // the sidecar filename and an unbounded one fails with ENAMETOOLONG and writes no
            // sidecar at all (seen on macOS, from an output dir or @TempDir far from the project).
            // And it has to be a property of the *layout* rather than of where the repository is
            // checked out, because it also appears in every VIBETAGS-MODULE marker in committed
            // output: an id derived from the absolute path made the same repository generate
            // different files on two machines, so check mode reported drift on a correct tree
            // (issue #436).
            //
            // The relative path satisfies both. It is short, it is the same from any checkout
            // location, and String.hashCode() is specified by the language rather than left to the
            // implementation the way Path.hashCode() is, so it agrees across JVMs. Separators are
            // normalised so Windows and Linux agree on the same layout. The directory name is kept
            // in front to leave the id legible.
            if (rel.getNameCount() > 0 && "..".equals(rel.getName(0).toString())) {
                String normalised = PathStrings.forwardSlashes(relStr);
                String hash = Integer.toHexString(normalised.hashCode() & 0x7fffffff);
                Path name = compilationRoot.getFileName();
                if (name == null) {
                    return hash;
                }
                String readable = sanitizeId(name.toString());
                if (readable.length() > MAX_READABLE_ID) {
                    readable = readable.substring(0, MAX_READABLE_ID);
                }
                return readable.isEmpty() ? hash : readable + "_" + hash;
            }
            return sanitizeId(relStr.replace(java.io.File.separatorChar, '_'));
        } catch (IllegalArgumentException e) {
            // Different filesystem roots (e.g. Windows drives), where there is no relative path to
            // derive anything from. This id still moves with the checkout, but the layout offers
            // nothing stable to replace it with.
            return Integer.toHexString(compilationRoot.hashCode() & 0x7fffffff);
        }
    }

    /**
     * True when {@code computeModuleId} could only produce a content hash — the compilation root
     * is not under the VibeTags root, so nothing about the module's location identifies it. The
     * caller warns, because an unrecognised id in a file that already carries named regions is far
     * more likely to be a mis-identified module than a genuinely new one (issue #331).
     */
    public static boolean isUnidentifiableModule(Path compilationRoot, Path vibetagsRoot) {
        if (compilationRoot.equals(vibetagsRoot)) return false;
        try {
            Path rel = vibetagsRoot.relativize(compilationRoot);
            return rel.getNameCount() > 0 && "..".equals(rel.getName(0).toString());
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** Strips anything that has no business in a filename. */
    public static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * The sidecar id for one source set of a module: the module id itself for the primary source
     * set, and {@code <moduleId>__<sourceSet>} for any other. Keeping them in separate files is
     * what stops the {@code test-compile} round from overwriting what {@code compile} wrote
     * (issue #330); they are merged back under one region id when the shared files are written.
     */
    public static String scopedModuleId(String moduleId, String sourceSet) {
        if (sourceSet == null || sourceSet.isBlank() || ModuleIdentity.MAIN.equals(sourceSet)) {
            return moduleId;
        }
        return moduleId + SOURCE_SET_SEPARATOR + sanitizeId(sourceSet);
    }

    /**
     * Computes the module path (relative to the VibeTags root) used for staleness detection.
     * Returns {@code ""} when the module IS the root.
     */
    public static String computeModulePath(Path compilationRoot, Path vibetagsRoot) {
        try {
            Path rel = vibetagsRoot.relativize(compilationRoot);
            String relStr = rel.toString();
            if (relStr.isEmpty() || ".".equals(relStr)) return "";
            // Out-of-tree compilation root (relative path escapes upward with ".."): there is no
            // meaningful module path under the root, and resolving the ".."-path for the staleness
            // check in readAll() is unreliable across symlinked temp dirs — notably macOS, where
            // /var -> /private/var adds a level, so the ".." count (derived from the symlink path)
            // over-shoots and lands on a non-existent directory, wrongly pruning the sidecar.
            // Treat it as root-like ("") so readAll() skips the directory-existence check entirely.
            if (rel.getNameCount() > 0 && "..".equals(rel.getName(0).toString())) return "";
            return relStr;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * The granular stems recorded by sidecars whose module directory no longer exists.
     *
     * <p>Must be called <em>before</em> {@link #readAll(Path)}, which deletes those sidecars: they
     * are the only record of which rule files the departed module wrote, and once they are gone
     * nothing can name the files for removal. That is why an orphaned rule file used to wait for
     * the root to compile — not because the evidence was weak, but because it had already been
     * thrown away by the time anyone could act on it.
     *
     * <p>A stale sidecar is positive evidence, unlike the absence a module round cannot argue from
     * (issue #383): the file existed, it named its stems, and its module's directory is gone.
     *
     * <p>Through {@link #listPaths}, which is where the "a sidecar is a non-{@code .tmp} file whose
     * name starts with the prefix" rule is kept, so this cannot drift from {@link #anyStale} and
     * {@link #readAll} about what counts as one. It also fixes an order that used to be the
     * filesystem's: the returned set is consumed in iteration order by
     * {@code GranularRulesWriter.removeStems}, whose result is logged as
     * {@code granular.departed.removed stems=}, so the same reactor printed the stems in a
     * different order on a different filesystem. Sorted by sidecar filename, it does not.
     */
    public static Set<String> staleGranularStems(Path root) {
        Set<String> stems = new LinkedHashSet<>();
        for (Path p : listPaths(root)) {
            String modulePath = readModulePathHeader(p);
            if (modulePath == null || modulePath.isEmpty() || "_root_".equals(modulePath)) {
                continue;
            }
            if (moduleDirExists(root, modulePath)) continue;
            ModuleSidecar stale = load(p);
            if (stale != null && stale != UNREADABLE && stale != FUTURE_VERSION) {
                stems.addAll(stale.getGranularStems());
            }
        }
        return stems;
    }

    /**
     * The region ids of modules that carry guardrails but nothing for {@code service}, and whose
     * sidecar was last written before {@code optedInAt} — that is, modules that last compiled
     * before this platform was opted into.
     *
     * <p>A sidecar records a body for every service active when its module ran, so a module that
     * has bodies but not this one has simply not been rebuilt since the opt-in file appeared. Its
     * guardrails are therefore missing from the merged file, which is well-formed and looks
     * complete. The mtime comparison is what keeps this from firing on a healthy build: absence
     * alone would also match a module that legitimately renders nothing for the service.
     *
     * <p>Returned rather than warned about here so the diagnostic stays in the processor, which
     * owns the messager and the user-facing wording.
     */
    public static List<String> modulesPredatingOptIn(Path root, String service, long optedInAt,
                                                     List<ModuleSidecar> sidecars) {
        List<String> behind = new ArrayList<>();
        for (ModuleSidecar sidecar : sidecars) {
            if (sidecar.getBodies().isEmpty() || sidecar.getBodies().containsKey(service)) {
                continue;
            }
            try {
                Path file = root.resolve(SIDECAR_PREFIX + sidecar.getModuleId());
                if (Files.getLastModifiedTime(file).toMillis() < optedInAt) {
                    behind.add(sidecar.getRegionId());
                }
            } catch (IOException ignored) {
                // Cannot date it, so cannot claim it is behind. Silence beats a wrong warning.
            }
        }
        Collections.sort(behind);
        return behind;
    }

    /**
     * True when any sidecar under {@code root} names a module directory that no longer exists, i.e.
     * when {@link #readAll(Path)} would prune something.
     *
     * <p>Exists for the fingerprint short-circuit, which returns before the merge that does the
     * pruning. Deleting a module from a reactor changes no annotation and moves no sidecar mtime,
     * so it is invisible to every other term of that condition: without this the merged files would
     * keep a deleted module's guardrails until some unrelated edit happened to change the
     * fingerprint.
     *
     * <p>Deliberately cheaper than {@code readAll}: it reads only far enough into each file to find
     * the {@code modulePath} header and decodes no bodies. An unreadable or headerless file counts
     * as not stale — the conservative answer, since the only cost of a false negative is one more
     * full round, while a false positive would delete a sibling's work.
     */
    public static boolean anyStale(Path root) {
        for (Path p : listPaths(root)) {
            String modulePath = readModulePathHeader(p);
            if (modulePath == null || modulePath.isEmpty() || "_root_".equals(modulePath)) {
                continue;
            }
            if (!moduleDirExists(root, modulePath)) return true;
        }
        return false;
    }

    /**
     * The module paths, relative to {@code root}, of sidecars whose module directory no longer
     * exists: the modules {@link #readAll(Path)} is about to retire. Like
     * {@link #staleGranularStems(Path)} it must be asked before that read, which deletes the only
     * record of them. The write cache needs the answer, because a departed module's nested output
     * went with its directory and nothing will ever write it again.
     */
    public static Set<String> staleModulePaths(Path root) {
        Set<String> paths = new LinkedHashSet<>();
        for (Path p : listPaths(root)) {
            String modulePath = readModulePathHeader(p);
            if (modulePath == null || modulePath.isEmpty() || "_root_".equals(modulePath)) {
                continue;
            }
            if (!moduleDirExists(root, modulePath)) {
                paths.add(modulePath);
            }
        }
        return paths;
    }

    /** Whether a sidecar's module directory is still there, and if not, why not. */
    enum ModuleDir {
        EXISTS,
        /** The directory is gone: the module was removed from the build. */
        MISSING,
        /**
         * This filesystem cannot even spell the path — a module called {@code api:v2} written on
         * Linux and read in a Windows checkout. Retired like a missing one, but a different fact
         * about the world, and the log says which.
         */
        UNREPRESENTABLE
    }

    /**
     * Whether the module directory a sidecar names still exists under {@code root}, and if not,
     * whether this filesystem could even represent the path.
     *
     * <p>A path this filesystem cannot represent counts as gone. A sidecar written on Linux for a
     * module directory called {@code api:v2} or {@code core } and synced into a Windows checkout
     * makes {@code root.resolve} throw InvalidPathException, a RuntimeException that escaped
     * readAll, staleGranularStems and anyStale and failed every module of the reactor with an
     * uncaught-exception diagnostic. The module cannot exist on this filesystem, so the answer
     * that keeps the build alive is also the true one: it is stale. The two cases are told apart
     * only so the prune event can name which one happened (issue #555).
     */
    private static ModuleDir moduleDir(Path root, String modulePath) {
        try {
            return Files.isDirectory(root.resolve(modulePath)) ? ModuleDir.EXISTS : ModuleDir.MISSING;
        } catch (java.nio.file.InvalidPathException unrepresentable) {
            return ModuleDir.UNREPRESENTABLE;
        }
    }

    /**
     * The module path of another live module whose sidecar sits under {@code moduleId}, or
     * {@code null} when there is none.
     *
     * <p>Module ids are sanitised paths, so {@code a/b} and {@code a_b} share one, and so do a
     * module directory named {@code core__test} and module {@code core}'s test source set. Two
     * modules with one id share one sidecar file and one region, and each build of one replaces
     * the other's guardrails in the shared files (#869). The sidecar on disk names the module that
     * last wrote it; a different path whose directory still exists is the other module. A path
     * that is gone is a renamed or removed module, which is no collision.
     */
    public static @Nullable String collidingModulePath(Path root, String moduleId, String modulePath) {
        ModuleSidecar existing = loadFor(root, moduleId);
        if (existing == null) {
            return null;
        }
        String theirs = normalizeModulePath(existing.modulePath);
        if (theirs.isEmpty() || theirs.equals(normalizeModulePath(modulePath)) || !moduleDirExists(root, theirs)) {
            return null;
        }
        return theirs;
    }

    /** {@link #moduleDir} as the yes/no question the callers that do not log ask. */
    private static boolean moduleDirExists(Path root, String modulePath) {
        return moduleDir(root, modulePath) == ModuleDir.EXISTS;
    }

    /**
     * Why {@link #load} refused a file, read back from its header. Cheap and only on the path where
     * a sidecar is already being dropped: {@code load} answers "not usable" with one {@code null},
     * and "written by an older processor" and "corrupt" are different facts about somebody's build.
     */
    private static String formatReason(Path sidecar) {
        Integer version = readFormatVersionHeader(sidecar);
        if (version == null) {
            return "no-version-header";
        }
        // MIN_READABLE_VERSION, not FORMAT_VERSION: a readable older format that failed to
        // parse is corrupt, and saying "stale-format" would blame the version for it.
        return version < MIN_READABLE_VERSION ? "stale-format" : "malformed";
    }

    /** The {@code # version} header of one sidecar, or {@code null} when absent or unparseable. */
    private static @Nullable Integer readFormatVersionHeader(Path sidecar) {
        try (java.io.BufferedReader reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith(VERSION_HEADER)) {
                    try {
                        return Integer.valueOf(line.substring(VERSION_HEADER.length()).trim());
                    } catch (NumberFormatException unparseable) {
                        return null;
                    }
                }
                if (!line.startsWith("#")) {
                    return null; // the version header is written first; past it there is none
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * A sidecar left out of this merge but kept on disk. DEBUG: in a parallel reactor this is
     * ordinary and self-correcting — the sibling's next build brings it back.
     */
    private static void logSkipped(@Nullable Logger log, Path sidecar, String reason) {
        if (log != null && log.isDebugEnabled()) {
            log.debug("sidecar.skip reason={} path={}", reason, GuardrailFileWriter.fileName(sidecar));
        }
    }

    /**
     * A sidecar dropped from this merge, deleted when {@code prune} is set. INFO rather than DEBUG,
     * because a module disappearing from every generated file is an outcome of the run and the
     * question people arrive with; when nothing is deleted it is a plain skip, and says so.
     */
    private static void logDropped(@Nullable Logger log, boolean prune, Path sidecar, String reason) {
        if (log == null) {
            return;
        }
        if (prune) {
            log.info("sidecar.prune reason={} path={}", reason, GuardrailFileWriter.fileName(sidecar));
        } else if (log.isDebugEnabled()) {
            log.debug("sidecar.skip reason={} path={}", reason, GuardrailFileWriter.fileName(sidecar));
        }
    }

    /** The {@code modulePath} header of one sidecar, or {@code null} if absent or unreadable. */
    private static @Nullable String readModulePathHeader(Path sidecar) {
        try (java.io.BufferedReader reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith(KEY_MODULE_PATH + "=")) {
                    // As load() reads it: readLine already drops the line ending, and a trim here
                    // turned a live module directory "core " into a missing "core" (#874).
                    return portable(line.substring(KEY_MODULE_PATH.length() + 1));
                }
                // The headers are written first; once a body line appears there is no header left.
                if (line.indexOf('=') > 0 && !line.startsWith("#") && !line.startsWith(KEY_MODULE_ID)
                        && !line.startsWith(KEY_REGION_ID)) {
                    return null;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable, mid-rename, or not UTF-8: not evidence of staleness. See the javadoc.
        }
        return null;
    }

    /**
     * A stamp over every sidecar in {@code root}: its name, mtime, size and content. A change in
     * any sibling changes the stamp, which invalidates the fingerprint short-circuit so this module
     * regenerates against the new merge.
     *
     * <p>Content is folded in, not just the mtime. Filesystem timestamp granularity is 1 s on
     * HFS+ and 2 s on FAT, and a reactor writes several sidecars per second: two saves inside one
     * tick left the stamp unchanged, so the sibling's edit stayed out of this module's output until
     * something else happened to move a timestamp (issue #556). The name is folded in for the same
     * reason — a module renamed with no edit is a different set of sidecars, and mtimes alone
     * cannot see that.
     *
     * <p>The read this costs is one pass over files the same build reads again in
     * {@link #readAll(Path)}, and a sidecar is the compressed form of one module's guardrails, not
     * a source tree.
     */
    public static long computeSidecarStamp(Path root) {
        long stamp = 0L;
        for (Path p : listPaths(root)) {
            try {
                stamp = 31L * stamp + GuardrailFileWriter.fileName(p).hashCode();
                java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                stamp = 31L * stamp + attrs.lastModifiedTime().toMillis();
                stamp = 31L * stamp + attrs.size();
                stamp = 31L * stamp + contentStamp(p);
            } catch (IOException ignored) {
                // A file that vanished between listing and stat contributes nothing to the stamp.
                // That is correct: it is not there, so it is not part of this round of state.
            }
        }
        return stamp;
    }

    /** 64-bit FNV-1a over a sidecar's bytes. Not cryptographic; it only has to notice an edit. */
    private static long contentStamp(Path sidecar) throws IOException {
        long hash = 0xcbf29ce484222325L;
        for (byte b : Files.readAllBytes(sidecar)) {
            hash = (hash ^ (b & 0xffL)) * 0x100000001b3L;
        }
        return hash;
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static void tryDelete(Path p) {
        try {

            Files.deleteIfExists(p);

        } catch (IOException ignored) {

            // Deleting a stale sidecar is housekeeping. If the file is locked or already gone,

            // the next run tries again; taking the build down for it would be the larger bug.

        }
    }
}
