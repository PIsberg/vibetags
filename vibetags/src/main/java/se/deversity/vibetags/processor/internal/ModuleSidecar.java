package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.processor.internal.content.GranularContribution;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.YamlMergeShape;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 */
@AICore(
    sensitivity = "high",
    note = "Per-module sidecar for multi-module Maven/Gradle builds; the .vibetags-mod-* file format is shared across independently compiled modules — format changes break backward compatibility"
)
public final class ModuleSidecar {

    static final String SIDECAR_PREFIX = ".vibetags-mod-";
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
    static final int FORMAT_VERSION = 2;
    private static final String KEY_FORMAT_VERSION = "# version";
    private static final String KEY_MODULE_ID = "moduleId";
    private static final String KEY_MODULE_PATH = "modulePath";
    private static final String KEY_REGION_ID = "regionId";

    /** Reserved body-key prefix; never a service key, so older readers ignore these entries. */
    private static final String RESERVED_PREFIX = "~";
    /** Key holding the granular rule stems this module+source-set wrote (newline separated). */
    private static final String KEY_GRANULAR_STEMS = "~granular";
    /** Key prefix for a module's own (nested) per-service body. */
    private static final String KEY_MODULE_BODY_PREFIX = "~mod~";
    /** Key prefix for the safety-tier digest used by the lean indexed reactor root. */
    private static final String KEY_INDEX_DIGEST_PREFIX = "~idx~";
    /** Key holding the annotated element ids this module+source-set contributed. */
    private static final String KEY_ELEMENT_IDS = "~elements";
    /**
     * Key prefix for one granular rule file's contribution from this module+source-set, keyed by
     * stem. Read back by {@link #mergeGranular} so a rule file several modules write ends up with
     * all of their rules instead of the last writer's (issue #365).
     */
    private static final String KEY_GRANULAR_UNIT_PREFIX = "~gran~";
    /** As above, for the module's own (nested) granular output; merged across source sets only. */
    private static final String KEY_MODULE_GRANULAR_UNIT_PREFIX = "~modgran~";

    /** Separator between a module id and its non-primary source set. */
    static final String SOURCE_SET_SEPARATOR = "__";

    private static final int MULTI_MODULE_THRESHOLD = 2;

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

    // --- Lean indexed-root state (transient; NEVER persisted by save(), so the on-disk sidecar
    // format is unchanged). Populated by readAll() only when the reactor root opts into the lean
    // index via a .vibetags-root-index file. ---

    /** True when the reactor root opted into the lean indexed aggregate (see {@code root_index}). */
    private boolean rootIndexMode = false;

    /**
     * For a non-root module in index mode: aggregate service key → the pointer text that should
     * REPLACE this module's embedded body in the reactor-root aggregate. Present only for the four
     * aggregate services with a granular sibling ({@code claude}/{@code cursor}/{@code windsurf}/
     * {@code copilot}) AND only when this module actually emits its own per-module output for that
     * service (so nothing is ever dropped — a module with no own output keeps its embedded body).
     */
    private final Map<String, String> indexPointers = new LinkedHashMap<>();

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
        this.modulePath = modulePath;
        this.regionId = regionId;
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

    public String getModuleId() { return moduleId; }
    public String getModulePath() { return modulePath; }
    public String getRegionId() { return regionId; }
    public Map<String, String> getBodies() { return Collections.unmodifiableMap(bodies); }
    public Map<String, String> getModuleBodies() { return Collections.unmodifiableMap(moduleBodies); }
    public Set<String> getGranularStems() { return Collections.unmodifiableSet(granularStems); }
    public Set<String> getElementIds() { return Collections.unmodifiableSet(elementIds); }
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
        Path tmp = root.resolve(SIDECAR_PREFIX + moduleId + ".tmp");

        StringBuilder sb = new StringBuilder();
        sb.append(KEY_FORMAT_VERSION).append('=').append(FORMAT_VERSION).append('\n');
        sb.append(KEY_MODULE_ID).append('=').append(moduleId).append('\n');
        sb.append(KEY_MODULE_PATH).append('=').append(modulePath).append('\n');
        sb.append(KEY_REGION_ID).append('=').append(regionId).append('\n');
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            appendEncoded(sb, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : moduleBodies.entrySet()) {
            appendEncoded(sb, KEY_MODULE_BODY_PREFIX + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : indexDigests.entrySet()) {
            appendEncoded(sb, KEY_INDEX_DIGEST_PREFIX + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, GranularContribution> entry : granularUnits.entrySet()) {
            appendEncoded(sb, KEY_GRANULAR_UNIT_PREFIX + entry.getKey(), entry.getValue().serialize());
        }
        for (Map.Entry<String, GranularContribution> entry : moduleGranularUnits.entrySet()) {
            appendEncoded(sb, KEY_MODULE_GRANULAR_UNIT_PREFIX + entry.getKey(), entry.getValue().serialize());
        }
        if (!granularStems.isEmpty()) {
            appendEncoded(sb, KEY_GRANULAR_STEMS, String.join("\n", granularStems));
        }
        if (!elementIds.isEmpty()) {
            appendEncoded(sb, KEY_ELEMENT_IDS, String.join("\n", elementIds));
        }

        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        moveIntoPlace(tmp, target, ATOMIC_REPLACE);
    }

    /** Attempts at renaming the temp file over the live sidecar before giving up. */
    static final int MOVE_ATTEMPTS = 10;
    /** Backoff step between move attempts; attempt N waits N × this. Worst case ≈ 275 ms. */
    private static final long MOVE_BACKOFF_MS = 5L;

    /**
     * How the temp file is renamed over the live sidecar. Injectable so the retry path can be
     * tested without manufacturing a real sharing violation, which only one OS produces.
     */
    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path target) throws IOException;
    }

    /** The production rename: atomic where the filesystem supports it. */
    static final FileMover ATOMIC_REPLACE = (source, target) -> {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException crossDevice) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    };

    /**
     * Renames the freshly written temp file over {@code target}, retrying briefly before failing.
     *
     * <p>Windows refuses a rename onto a file that another process has open, and
     * {@link #readAll(Path)} in a sibling module's compilation opens exactly this file. In a
     * parallel reactor ({@code mvn -T}, {@code gradle --parallel}) that collision is reachable and
     * arrives as {@code AccessDeniedException} — which would abort this module's save and drop its
     * entire contribution from the merged output for that build, the failure the sidecar exists to
     * prevent. A reader holds the handle for microseconds, so a bounded retry trades a lost module
     * for a few milliseconds. {@code ModuleSidecarAsyncTest} reproduces the collision; without this
     * retry it fails on Windows within a second.
     */
    static void moveIntoPlace(Path tmp, Path target, FileMover mover) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                mover.move(tmp, target);
                return;
            } catch (java.nio.file.FileSystemException e) {
                last = e;
                if (attempt == MOVE_ATTEMPTS) break;
                try {
                    Thread.sleep(MOVE_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    // Stop retrying, but report the move failure rather than the interruption:
                    // that is the one the caller has to act on.
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        tryDelete(tmp);
        throw last;
    }

    private static void appendEncoded(StringBuilder sb, String key, String value) {
        sb.append(key).append('=')
          .append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
          .append('\n');
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
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String moduleId = null;
            String modulePath = "";
            String regionId = null;
            boolean sawCurrentVersion = false;
            Map<String, String> bodies = new LinkedHashMap<>();
            Map<String, String> moduleBodies = new LinkedHashMap<>();
            Map<String, String> indexDigests = new LinkedHashMap<>();
            Set<String> granularStems = new LinkedHashSet<>();
            Set<String> elementIds = new LinkedHashSet<>();
            Map<String, GranularContribution> granularUnits = new LinkedHashMap<>();
            Map<String, GranularContribution> moduleGranularUnits = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.startsWith("#")) {
                    // Enforce the format-version header: refuse to (mis-)parse future formats,
                    // and treat older formats (or a missing header) as stale.
                    if (line.startsWith(KEY_FORMAT_VERSION + "=")) {
                        try {
                            int version = Integer.parseInt(
                                    line.substring(KEY_FORMAT_VERSION.length() + 1).trim());
                            if (version > FORMAT_VERSION) return FUTURE_VERSION;
                            if (version < FORMAT_VERSION) return null;
                            sawCurrentVersion = true;
                        } catch (NumberFormatException malformed) {
                            return null;
                        }
                    }
                    continue; // other comments — skip
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq);
                String val = line.substring(eq + 1);
                if (KEY_MODULE_ID.equals(key)) {
                    moduleId = val;
                } else if (KEY_MODULE_PATH.equals(key)) {
                    modulePath = val;
                } else if (KEY_REGION_ID.equals(key)) {
                    regionId = val;
                } else if (KEY_GRANULAR_STEMS.equals(key)) {
                    // -1 keeps trailing empty fields rather than relying on split's default of
                    // dropping them; the isBlank guard is what discards them, visibly.
                    for (String stem : decode(val).split("\n", -1)) {
                        if (!stem.isBlank()) granularStems.add(stem);
                    }
                } else if (KEY_ELEMENT_IDS.equals(key)) {
                    for (String id : decode(val).split("\n", -1)) {
                        if (!id.isBlank()) elementIds.add(id);
                    }
                } else if (key.startsWith(KEY_MODULE_GRANULAR_UNIT_PREFIX)) {
                    // Checked before the ~mod~ prefix below: "~modgran~" does not start with
                    // "~mod~", but the two read alike and the order is what keeps that true.
                    putParsedContribution(moduleGranularUnits,
                        key.substring(KEY_MODULE_GRANULAR_UNIT_PREFIX.length()), decode(val));
                } else if (key.startsWith(KEY_GRANULAR_UNIT_PREFIX)) {
                    putParsedContribution(granularUnits,
                        key.substring(KEY_GRANULAR_UNIT_PREFIX.length()), decode(val));
                } else if (key.startsWith(KEY_MODULE_BODY_PREFIX)) {
                    moduleBodies.put(key.substring(KEY_MODULE_BODY_PREFIX.length()), decode(val));
                } else if (key.startsWith(KEY_INDEX_DIGEST_PREFIX)) {
                    indexDigests.put(key.substring(KEY_INDEX_DIGEST_PREFIX.length()), decode(val));
                } else if (key.startsWith(RESERVED_PREFIX)) {
                    // Reserved key from a newer processor: recognised, deliberately not stored, and
                    // above all kept out of the `else` below, which would put it in `bodies` and
                    // render it into somebody's guardrail file. Doing nothing here is the point.
                } else {
                    bodies.put(key, decode(val));
                }
            }
            if (moduleId == null || !sawCurrentVersion) return null;
            // Sidecars written before the source-set split carry no regionId; their module id IS
            // the region id, which is exactly what they meant.
            ModuleSidecar s = new ModuleSidecar(moduleId, modulePath,
                    regionId != null && !regionId.isBlank() ? regionId : moduleId);
            s.bodies.putAll(bodies);
            s.moduleBodies.putAll(moduleBodies);
            s.indexDigests.putAll(indexDigests);
            s.granularStems.addAll(granularStems);
            s.elementIds.addAll(elementIds);
            s.granularUnits.putAll(granularUnits);
            s.moduleGranularUnits.putAll(moduleGranularUnits);
            return s;
        } catch (IOException unreadable) {
            // Could not read it — that says nothing about the content. See UNREADABLE.
            return UNREADABLE;
        } catch (IllegalArgumentException malformed) {
            // Read it fine, but a value is not decodable: genuinely corrupt, so the caller prunes.
            return null;
        }
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

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
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
     */
    public static List<ModuleSidecar> readAll(Path root) {
        if (!Files.isDirectory(root)) return new ArrayList<>();
        List<ModuleSidecar> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> {
                      // Path.getFileName() returns null only for root paths — guard for correctness.
                      Path fn = p.getFileName();
                      return fn != null && fn.toString().startsWith(SIDECAR_PREFIX);
                  })
                  .filter(p -> {
                      Path fn = p.getFileName();
                      return fn == null || !fn.toString().endsWith(".tmp");
                  })
                  .sorted(Comparator.comparing(p -> {
                      Path fn = p.getFileName();
                      return fn != null ? fn.toString() : "";
                  }))
                  .forEach(p -> {
                      ModuleSidecar s = load(p);
                      if (s == UNREADABLE) {
                          // Locked, vanished, or being renamed over by the module that owns it —
                          // a sibling save in a parallel reactor does exactly that on Windows.
                          // Skip this round (readAll already tolerates a missing sibling) and,
                          // above all, do not delete a file we never managed to look at.
                          return;
                      }
                      if (s == null) {
                          tryDelete(p);
                          return;
                      }
                      if (s == FUTURE_VERSION) {
                          // Written by a newer processor in a mixed-version build: skip it (its
                          // module's content is missing from OUR merge, the newer module merges
                          // everything correctly) but never delete a sibling's valid sidecar.
                          return;
                      }
                      // Stale check: if the module path (relative to root) no longer exists, prune.
                      if (!s.modulePath.isEmpty() && !"_root_".equals(s.modulePath)) {
                          Path moduleDir = root.resolve(s.modulePath);
                          if (!Files.isDirectory(moduleDir)) {
                              tryDelete(p);
                              return;
                          }
                      }
                      result.add(s);
                  });
        } catch (IOException ignored) {

            // Best-effort listing: a root we cannot read contributes no sidecars, which is the

            // same outcome as a root with none. Failing here would fail a compile over a

            // directory the build does not need.

        }
        applyRootIndexModeTo(root, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Lean indexed-root (multi-module): link to per-module rules instead of embedding
    // -----------------------------------------------------------------------

    /** Aggregate services that have a glob-scoped granular sibling and can therefore be linked. */
    public static final List<String> INDEXABLE_AGGREGATES = List.of("claude", "cursor", "windsurf", "copilot");

    /**
     * When the reactor root opted into the lean index ({@code .vibetags-root-index} present), flags
     * every sidecar and precomputes, for each non-root module, the pointer text that replaces its
     * embedded body in the root aggregate. Runs here (not in {@code mergeFor}) because it needs the
     * filesystem — the module's own opt-in files determine where its guardrails live — keeping
     * {@code mergeFor} disk-free and its @AIContract signature untouched.
     */
    public static void applyRootIndexModeTo(Path root, List<ModuleSidecar> sidecars) {
        if (!Files.exists(root.resolve(".vibetags-root-index"))) return;
        for (ModuleSidecar s : sidecars) {
            s.rootIndexMode = true;
            // The root module's own guardrails are not duplicated elsewhere — keep them inline.
            if (s.modulePath.isEmpty() || "_root_".equals(s.moduleId)) continue;
            Path moduleDir = root.resolve(s.modulePath);
            if (!Files.isDirectory(moduleDir)) continue;
            java.util.Set<String> moduleActive =
                ServiceRegistry.resolveActiveServices(ServiceRegistry.buildServiceFileMap(moduleDir));
            for (String agg : INDEXABLE_AGGREGATES) {
                if (!s.bodies.containsKey(agg)) continue; // module contributes nothing for this service
                String pointer = buildIndexPointer(agg, s.modulePath, moduleActive);
                if (pointer != null) s.indexPointers.put(agg, pointer);
            }
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

    /** Glob-scoped granular directory (no trailing slash) for an aggregate service, else {@code null}. */
    private static @Nullable String aggregateScopedDir(String service) {
        switch (service) {
            case "claude":   return ".claude/rules";
            case "cursor":   return ".cursor/rules";
            case "windsurf": return ".windsurf/rules";
            case "copilot":  return ".github/instructions";
            default:         return null;
        }
    }

    /** Granular service key governing an aggregate service (e.g. {@code claude} → {@code claude_granular}). */
    private static @Nullable String aggregateGranularKey(String service) {
        return aggregateScopedDir(service) == null ? null : service + "_granular";
    }

    /** The always-loaded aggregate file name for an aggregate service, else {@code null}. */
    private static @Nullable String aggregateFileName(String service) {
        switch (service) {
            case "claude":   return "CLAUDE.md";
            case "cursor":   return ".cursorrules";
            case "windsurf": return ".windsurfrules";
            case "copilot":  return ".github/copilot-instructions.md";
            default:         return null;
        }
    }

    /**
     * Builds the pointer text that replaces a module's embedded aggregate body, or {@code null} when
     * the module has no per-module output for this service (in which case the body stays embedded so
     * no guardrails are lost). {@code moduleActive} is the module directory's own file-existence
     * opt-in set — the pointer names only the files the module actually generates.
     */
    private static @Nullable String buildIndexPointer(String service, String modulePath,
                                                      java.util.Set<String> moduleActive) {
        String scopedDir = aggregateScopedDir(service);
        if (scopedDir == null) return null;
        boolean hasGranular = moduleActive.contains(aggregateGranularKey(service));
        boolean hasAggregate = moduleActive.contains(service);
        if (!hasGranular && !hasAggregate) return null; // module keeps nothing of its own → embed as before

        String mp = modulePath.replace('\\', '/');
        StringBuilder p = new StringBuilder();
        p.append("Guardrails for module `").append(mp).append("` are maintained in that module's own files");
        if (hasGranular) {
            p.append(", in the scoped rules under `").append(mp).append('/').append(scopedDir)
             .append("/` (loaded automatically when you open a matching source file)");
        }
        if (hasAggregate) {
            p.append(hasGranular ? " and `" : ", in `").append(mp).append('/').append(aggregateFileName(service)).append('`');
        }
        p.append(". Consult those for this module's full guardrails.");
        return p.toString();
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
        boolean indexMode = isRootIndexMode(sidecars);
        List<Map.Entry<String, String>> contributions = new ArrayList<>();
        boolean anyPointer = false;
        for (Map.Entry<String, List<ModuleSidecar>> region : groupByRegion(sidecars).entrySet()) {
            List<String> parts = new ArrayList<>();
            String pointer = null;
            for (ModuleSidecar s : region.getValue()) {
                String body = s.bodies.get(serviceKey);
                if (body == null || body.isBlank()) continue;
                // In lean-index mode a module that maintains its own per-module output for this
                // service contributes its safety-tier digest plus a short pointer instead of its
                // full body (see applyRootIndexMode() and issue #332).
                String p = indexMode ? s.indexPointers.get(serviceKey) : null;
                if (p != null) {
                    pointer = p;
                    String digest = s.indexDigests.get(serviceKey);
                    if (digest != null && !digest.isBlank()) parts.add(digest.strip());
                } else {
                    parts.add(body.strip());
                }
            }
            if (pointer != null) {
                anyPointer = true;
                parts.add(pointer); // one pointer per module, after every source set's digest
            }
            if (parts.isEmpty()) continue;
            contributions.add(new AbstractMap.SimpleEntry<>(region.getKey(), String.join("\n\n", parts)));
        }
        if (contributions.isEmpty()) return "";
        // Historical behaviour is preserved whenever no pointer applies: a lone contribution is
        // returned verbatim (no sub-markers), multiple are wrapped below. When at least one module
        // was linked, always wrap so every pointer keeps its owning-module sub-marker context.
        if (!anyPointer && contributions.size() < MULTI_MODULE_THRESHOLD) return contributions.get(0).getValue();

        // A YAML platform has one rules:/reviews:/customModes: key, so stacking whole documents
        // below produces a file whose top-level key repeats once per module — rejected by a strict
        // parser, silently truncated to the last module by a lenient one. Those platforms declare
        // where their scaffold ends and get it written once, with every module's entries under it.
        // A null shape means the platform concatenates fine; a null merge means the declared anchor
        // was not in the rendering (a renderer changed without its shape), and stacking is then
        // still the better of the two wrong answers.
        YamlMergeShape shape = PlatformRendererRegistry.mergeShapeFor(serviceKey);
        if (shape != null) {
            String document = shape.merge(contributions,
                id -> htmlMarkers ? String.format(SUB_MARKER_MD_FORMAT, id)
                                  : String.format(SUB_MARKER_HASH_FORMAT, id),
                id -> htmlMarkers ? String.format(SUB_MARKER_MD_END_FORMAT, id)
                                  : String.format(SUB_MARKER_HASH_END_FORMAT, id));
            if (document != null) return document.strip();
        }

        StringBuilder merged = new StringBuilder();
        for (Map.Entry<String, String> entry : contributions) {
            String id = entry.getKey();
            if (htmlMarkers) {
                merged.append(String.format(SUB_MARKER_MD_FORMAT, id));
            } else {
                merged.append(String.format(SUB_MARKER_HASH_FORMAT, id));
            }
            merged.append('\n').append(entry.getValue()).append('\n');
            if (htmlMarkers) {
                merged.append(String.format(SUB_MARKER_MD_END_FORMAT, id));
            } else {
                merged.append(String.format(SUB_MARKER_HASH_END_FORMAT, id));
            }
            merged.append('\n');
        }
        return merged.toString().strip();
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
     * @param serviceKey e.g. {@code "mentat"}
     * @param sidecars   all known module sidecars (current + siblings)
     */
    public static List<Map.Entry<String, String>> contributionsFor(String serviceKey,
                                                                   List<ModuleSidecar> sidecars) {
        List<Map.Entry<String, String>> contributions = new ArrayList<>();
        for (Map.Entry<String, List<ModuleSidecar>> region : groupByRegion(sidecars).entrySet()) {
            List<String> parts = new ArrayList<>();
            for (ModuleSidecar s : region.getValue()) {
                String body = s.bodies.get(serviceKey);
                if (body != null && !body.isBlank()) {
                    parts.add(body);
                }
            }
            if (!parts.isEmpty()) {
                contributions.add(new AbstractMap.SimpleEntry<>(region.getKey(), parts.get(0)));
                // A second source set of the same module renders the same whole-file document from
                // a different slice of the code; both are offered so the merge unions them.
                for (int i = 1; i < parts.size(); i++) {
                    contributions.add(new AbstractMap.SimpleEntry<>(region.getKey(), parts.get(i)));
                }
            }
        }
        return contributions;
    }

    /**
     * Merges the <em>module's own</em> nested output for {@code serviceKey} across the source sets
     * of one region, main first (sidecars arrive sorted by filename, and the main source set's id
     * carries no suffix). Returns {@code ""} when no sidecar in the region has a body — the caller
     * then falls back to this compilation's freshly built content.
     */
    public static String mergeModuleBodies(String serviceKey, List<ModuleSidecar> sidecars, String regionId) {
        List<String> parts = new ArrayList<>();
        for (ModuleSidecar s : sidecars) {
            if (!s.regionId.equals(regionId)) continue;
            String body = s.moduleBodies.get(serviceKey);
            if (body != null && !body.isBlank()) parts.add(body.strip());
        }
        return String.join("\n\n", parts);
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
     * (<a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>). Contributions are
     * grouped by region first, so a module compiled once per source set is one contributor and its
     * source sets are simply concatenated; several regions are wrapped in {@code VIBETAGS-MODULE}
     * sub-markers, exactly as the aggregate files are. Granular files are markdown on every
     * platform, so the HTML marker form always applies.
     *
     * <p>A lone contributor's body is returned verbatim, which keeps the single-module output
     * byte-for-byte what it was. Globs are unioned across contributors in the same encounter order
     * for every module, so which module compiles last cannot change the file.
     */
    public static Map<String, GranularContribution> mergeGranular(List<ModuleSidecar> sidecars) {
        return mergeGranularUnits(sidecars, s -> s.granularUnits, true, null);
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
        return mergeGranularUnits(sidecars, s -> s.moduleGranularUnits, false, regionId);
    }

    private static Map<String, GranularContribution> mergeGranularUnits(
            List<ModuleSidecar> sidecars,
            java.util.function.Function<ModuleSidecar, Map<String, GranularContribution>> source,
            boolean subMarkers,
            @Nullable String regionOnly) {
        // stem → region → the contributions of that region's source sets, in encounter order.
        Map<String, Map<String, List<GranularContribution>>> byStem = new LinkedHashMap<>();
        for (ModuleSidecar s : sidecars) {
            if (regionOnly != null && !s.regionId.equals(regionOnly)) continue;
            source.apply(s).forEach((stem, contribution) -> {
                if (contribution.isEmpty()) return;
                byStem.computeIfAbsent(stem, k -> new LinkedHashMap<>())
                      .computeIfAbsent(s.regionId, k -> new ArrayList<>())
                      .add(contribution);
            });
        }

        Map<String, GranularContribution> merged = new LinkedHashMap<>();
        byStem.forEach((stem, byRegion) -> {
            Set<String> globs = new LinkedHashSet<>();
            List<Map.Entry<String, String>> regionBodies = new ArrayList<>();
            byRegion.forEach((region, contributions) -> {
                List<String> parts = new ArrayList<>();
                for (GranularContribution c : contributions) {
                    globs.addAll(c.globs());
                    parts.add(c.body().strip());
                }
                regionBodies.add(new AbstractMap.SimpleEntry<>(region, String.join("\n\n", parts)));
            });
            merged.put(stem, new GranularContribution(new ArrayList<>(globs),
                joinRegions(regionBodies, subMarkers)));
        });
        return merged;
    }

    /** One region → its body verbatim; several → each wrapped in its own module sub-markers. */
    private static String joinRegions(List<Map.Entry<String, String>> regionBodies, boolean subMarkers) {
        if (!subMarkers || regionBodies.size() < MULTI_MODULE_THRESHOLD) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> entry : regionBodies) {
                parts.add(entry.getValue());
            }
            return String.join("\n\n", parts);
        }
        StringBuilder merged = new StringBuilder();
        for (Map.Entry<String, String> entry : regionBodies) {
            merged.append(String.format(SUB_MARKER_MD_FORMAT, entry.getKey())).append('\n')
                  .append(entry.getValue()).append('\n')
                  .append(String.format(SUB_MARKER_MD_END_FORMAT, entry.getKey())).append('\n');
        }
        return merged.toString().strip();
    }

    /** Sidecars grouped by region id, preserving the (filename-sorted) encounter order. */
    private static Map<String, List<ModuleSidecar>> groupByRegion(List<ModuleSidecar> sidecars) {
        Map<String, List<ModuleSidecar>> byRegion = new LinkedHashMap<>();
        for (ModuleSidecar s : sidecars) {
            byRegion.computeIfAbsent(s.regionId, k -> new ArrayList<>()).add(s);
        }
        return byRegion;
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
            // If the compilation root is not *under* the VibeTags root, the relative path escapes
            // upward (starts with ".."). Such an id is meaningless and, worse, unbounded in length:
            // an output dir or @TempDir far from the project can produce a filename that exceeds the
            // OS limit (seen on macOS, where save() then fails with ENAMETOOLONG and no sidecar is
            // written). Fall back to a short stable hash, as the different-root case below does.
            if (rel.getNameCount() > 0 && "..".equals(rel.getName(0).toString())) {
                return Integer.toHexString(compilationRoot.hashCode() & 0x7fffffff);
            }
            return sanitizeId(relStr.replace(java.io.File.separatorChar, '_'));
        } catch (IllegalArgumentException e) {
            // Different filesystem roots (e.g., Windows different drives)
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
     * Computes a lightweight stamp over all sidecar mtimes. A change in any sibling sidecar
     * changes the stamp, invalidating the fingerprint short-circuit so this module regenerates.
     */
    public static long computeSidecarStamp(Path root) {
        long stamp = 0L;
        for (Path p : listPaths(root)) {
            try {
                stamp = 31L * stamp + Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ignored) {
                // A file that vanished between listing and stat contributes nothing to the stamp.
                // That is correct: it is not there, so it is not part of this round of state.
            }
        }
        return stamp;
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
