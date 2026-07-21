package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
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
import java.util.List;
import java.util.Map;
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
     */
    static final int FORMAT_VERSION = 2;
    private static final String KEY_FORMAT_VERSION = "# version";
    private static final String KEY_MODULE_ID = "moduleId";
    private static final String KEY_MODULE_PATH = "modulePath";

    private static final int MULTI_MODULE_THRESHOLD = 2;

    /** Module sub-marker embedded inside the outer VIBETAGS-START/END block. */
    public static final String SUB_MARKER_MD_FORMAT = "<!-- VIBETAGS-MODULE: %s -->";
    public static final String SUB_MARKER_MD_END_FORMAT = "<!-- VIBETAGS-MODULE-END: %s -->";
    public static final String SUB_MARKER_HASH_FORMAT = "# VIBETAGS-MODULE: %s";
    public static final String SUB_MARKER_HASH_END_FORMAT = "# VIBETAGS-MODULE-END: %s";

    private final String moduleId;
    private final String modulePath;
    private final Map<String, String> bodies = new LinkedHashMap<>();

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
        this.moduleId = moduleId;
        this.modulePath = modulePath;
    }

    /** Stores the rendered body for {@code serviceKey} if non-blank. */
    public void putBody(String serviceKey, String body) {
        if (body != null && !body.isBlank()) {
            bodies.put(serviceKey, body);
        }
    }

    public String getModuleId() { return moduleId; }
    public Map<String, String> getBodies() { return Collections.unmodifiableMap(bodies); }

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
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            String encoded = Base64.getEncoder().encodeToString(
                    entry.getValue().getBytes(StandardCharsets.UTF_8));
            sb.append(entry.getKey()).append('=').append(encoded).append('\n');
        }

        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Sentinel returned by {@link #load(Path)} for a sidecar written in a newer format than this
     * processor understands. Distinct from {@code null} (malformed): a future-version sidecar is
     * owned by a sibling module compiled with a newer processor and must be skipped, not deleted.
     */
    static final ModuleSidecar FUTURE_VERSION = new ModuleSidecar("_future_", "");

    /**
     * Loads a sidecar from {@code path}. Returns {@code null} if the file is malformed or was
     * written in an older format than {@link #FORMAT_VERSION} (stale — see the version history
     * on {@code FORMAT_VERSION}; the caller prunes it), or {@link #FUTURE_VERSION} if its
     * {@code # version} header is newer than {@link #FORMAT_VERSION} (owned by a newer sibling —
     * skipped but never deleted).
     */
    static @Nullable ModuleSidecar load(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String moduleId = null;
            String modulePath = "";
            boolean sawCurrentVersion = false;
            Map<String, String> bodies = new LinkedHashMap<>();
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
                } else {
                    bodies.put(key,
                        new String(Base64.getDecoder().decode(val), StandardCharsets.UTF_8));
                }
            }
            if (moduleId == null || !sawCurrentVersion) return null;
            ModuleSidecar s = new ModuleSidecar(moduleId, modulePath);
            s.bodies.putAll(bodies);
            return s;
        } catch (IOException | IllegalArgumentException e) {
            return null;
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
        } catch (IOException ignored) {}
        applyRootIndexMode(root, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Lean indexed-root (multi-module): link to per-module rules instead of embedding
    // -----------------------------------------------------------------------

    /** Aggregate services that have a glob-scoped granular sibling and can therefore be linked. */
    private static final String[] INDEXABLE_AGGREGATES = {"claude", "cursor", "windsurf", "copilot"};

    /**
     * When the reactor root opted into the lean index ({@code .vibetags-root-index} present), flags
     * every sidecar and precomputes, for each non-root module, the pointer text that replaces its
     * embedded body in the root aggregate. Runs here (not in {@code mergeFor}) because it needs the
     * filesystem — the module's own opt-in files determine where its guardrails live — keeping
     * {@code mergeFor} disk-free and its @AIContract signature untouched.
     */
    private static void applyRootIndexMode(Path root, List<ModuleSidecar> sidecars) {
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

    /** True when at least one sidecar carries the reactor-root lean-index opt-in. */
    private static boolean isRootIndexMode(List<ModuleSidecar> sidecars) {
        for (ModuleSidecar s : sidecars) {
            if (s.rootIndexMode) return true;
        }
        return false;
    }

    /** Glob-scoped granular directory (no trailing slash) for an aggregate service, else {@code null}. */
    private static String aggregateScopedDir(String service) {
        switch (service) {
            case "claude":   return ".claude/rules";
            case "cursor":   return ".cursor/rules";
            case "windsurf": return ".windsurf/rules";
            case "copilot":  return ".github/instructions";
            default:         return null;
        }
    }

    /** Granular service key governing an aggregate service (e.g. {@code claude} → {@code claude_granular}). */
    private static String aggregateGranularKey(String service) {
        return aggregateScopedDir(service) == null ? null : service + "_granular";
    }

    /** The always-loaded aggregate file name for an aggregate service, else {@code null}. */
    private static String aggregateFileName(String service) {
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
    private static String buildIndexPointer(String service, String modulePath, java.util.Set<String> moduleActive) {
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
        } catch (IOException ignored) {}
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
     * @param serviceKey  e.g. {@code "cursor"}, {@code "claude"}
     * @param sidecars    all known module sidecars (current + siblings)
     * @param htmlMarkers {@code true} for {@code <!-- -->} style, {@code false} for {@code #} style
     */
    @AIContract(reason = "Sub-marker format constants (SUB_MARKER_*_FORMAT) are embedded in generated CLAUDE.md and .cursorrules; changing them silently corrupts multi-module merged output on the next compile")
    public static String mergeFor(String serviceKey, List<ModuleSidecar> sidecars, boolean htmlMarkers) {
        boolean indexMode = isRootIndexMode(sidecars);
        List<Map.Entry<String, String>> contributions = new ArrayList<>();
        boolean anyPointer = false;
        for (ModuleSidecar s : sidecars) {
            String body = s.bodies.get(serviceKey);
            if (body == null || body.isBlank()) continue;
            // In lean-index mode a module that maintains its own per-module output for this service
            // contributes a short pointer instead of its full body (see applyRootIndexMode()).
            String pointer = indexMode ? s.indexPointers.get(serviceKey) : null;
            if (pointer != null) {
                anyPointer = true;
                contributions.add(new AbstractMap.SimpleEntry<>(s.moduleId, pointer));
            } else {
                contributions.add(new AbstractMap.SimpleEntry<>(s.moduleId, body.strip()));
            }
        }
        if (contributions.isEmpty()) return "";
        // Historical behaviour is preserved whenever no pointer applies: a lone contribution is
        // returned verbatim (no sub-markers), multiple are wrapped below. When at least one module
        // was linked, always wrap so every pointer keeps its owning-module sub-marker context.
        if (!anyPointer && contributions.size() < MULTI_MODULE_THRESHOLD) return contributions.get(0).getValue();

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
            return relStr.replace(java.io.File.separatorChar, '_').replaceAll("[^a-zA-Z0-9._-]", "_");
        } catch (IllegalArgumentException e) {
            // Different filesystem roots (e.g., Windows different drives)
            return Integer.toHexString(compilationRoot.hashCode() & 0x7fffffff);
        }
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
            } catch (IOException ignored) {}
        }
        return stamp;
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static void tryDelete(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
