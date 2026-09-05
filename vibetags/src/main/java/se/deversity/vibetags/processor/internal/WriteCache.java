package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-output-file content cache. Lets {@link GuardrailFileWriter} skip the
 * read-and-compare path when the file's size+mtime are unchanged since we last
 * wrote it AND the about-to-be-written content hashes to the value we cached.
 *
 * <p>Persisted as a tab-separated text file at the project root
 * (default: {@code .vibetags-cache}). Format per line:
 *
 * <pre>{@code
 * <absolute-path>\t<sha256-hex-of-body>\t<file-size>\t<file-mtime-millis>
 * }</pre>
 *
 * <p>Safe to delete: the cache is purely an optimisation — if missing or corrupt,
 * the writer falls back to the existing read-compare-write path and rebuilds the
 * cache from scratch on the next successful write.
 *
 * <p>Not thread-safe across instances. A single processor invocation owns one
 * instance for its lifetime; concurrent compilations should use disjoint roots.
 */
@AICore(
    sensitivity = "high",
    note = "Per-file content cache backed by .vibetags-cache; false positives (wrongly treating stale output as unchanged) would silently corrupt generated files"
)
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.SYNCHRONIZED,
    note = "Safe for concurrent calls on one instance (WriteCacheAsyncTest proves it); instances must own disjoint roots, because two instances over the same .vibetags-cache race by design"
)
@AITestDriven(
    coverageGoal = 90,
    framework = AITestDriven.Framework.JUNIT_5,
    mockPolicy = "Write the failing test first; drive real files in a temp dir, never a mocked filesystem (a false cache positive silently corrupts output)"
)
public final class WriteCache {

    /**
     * Format version written into the cache header. Bump when the line format changes.
     * A cache written by a newer processor (higher version) is discarded wholesale on load —
     * never mis-parsed — and rebuilt by this run. Caches without a {@code # format:} header
     * (pre-1.0) use the same line format as version 1 and load normally.
     *
     * <p>Version 2 introduced watched-input entries ({@link #INPUT_HASH}); the line format is
     * unchanged, but only a version that knows to prune them can be allowed to keep them — an
     * older processor never re-records such an entry, so a deleted input would suppress its
     * short-circuit forever. Bumping the version makes that processor discard the cache instead.
     */
    static final int FORMAT_VERSION = 3;

    /**
     * A module's own run headers. Version 3 keeps one of these per module id: the fingerprint
     * and the run context a module recorded are questions about that module's last round, and
     * one set for the whole root meant every module's flush overwrote its sibling's, so a no-op
     * reactor rebuild short-circuited in at most the module that flushed last (issue #556). The
     * empty id is the pre-version-3 whole-root section, kept so a cache written before the split
     * still answers for the first module that adopts it.
     *
     * <p>The sidecar stamp is recorded both root-wide (reflecting the state when any module last
     * wrote) and per-module (reflecting the state when this specific module last compiled). Both
     * must match:
     * <ul>
     *   <li>A root-wide stamp that differs from disk means a sidecar was deleted or modified
     *       externally (e.g. retiring an emptied module), preventing stale short-circuits.</li>
     *   <li>A module section stamp that differs from the root-wide stamp means a sibling module
     *       compiled and updated the sidecar set since this module last ran, so this module must
     *       regenerate and catch up before it can short-circuit (issue #556, issue #525).</li>
     * </ul>
     */
    private static final class Section {
        @Nullable String fingerprint;
        @Nullable String storedContext;
        @Nullable String sidecarStamp;
    }

    /** The whole-root section a version-2 cache carries, and an unbound instance still uses. */
    private static final String LEGACY_SECTION = "";

    /** Cache record. */
    static final class Entry {
        final String hash;
        final long size;
        final long mtime;
        Entry(String hash, long size, long mtime) {
            this.hash = hash;
            this.size = size;
            this.mtime = mtime;
        }
    }

    private final Path cachePath;
    private final Path rootDir;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;
    private boolean dirty;

    /** Per-module run headers, keyed by module id; see {@link Section}. */
    private final Map<String, Section> sections = new LinkedHashMap<>();

    /** Stamp over the sidecar set as the last full round of any module left it; {@code null} when unknown. */
    private @Nullable String sidecarStamp;

    /** The module this compilation records under ({@link #bindModule}); the legacy section until bound. */
    private String currentModule = LEGACY_SECTION;

    /** Run context bound by the current compilation ({@link #bindContext}). {@code null} when unbound. */
    private @Nullable String currentContext;

    public WriteCache(Path cachePath) {
        this.cachePath = cachePath;
        Path parent = cachePath.getParent();
        this.rootDir = parent != null ? parent.toAbsolutePath().normalize() : java.nio.file.Paths.get("").toAbsolutePath().normalize();
    }

    /**
     * Stable cache key for a file, relative to {@link #rootDir} where possible. Falls back to the
     * absolute path when the file lives on a different filesystem root than {@code rootDir} — e.g.
     * a {@code @TempDir} on a different Windows drive than the project — which would otherwise make
     * {@link Path#relativize} throw {@code IllegalArgumentException: 'other' has different root}.
     */
    private String cacheKey(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        try {
            return rootDir.relativize(abs).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoot) {
            return abs.toString().replace('\\', '/');
        }
    }

    /**
     * Returns the persisted top-level build fingerprint from the previous successful run, or
     * {@code null} if no fingerprint is on file. The fingerprint covers the entire annotation
     * input set plus the active service set — see {@link BuildFingerprint}. Reported as absent
     * when the run context bound via {@link #bindContext} differs from the stored one.
     */
    public synchronized @Nullable String getBuildFingerprint() {
        loadIfNeeded();
        Section section = sections.get(currentModule);
        if (section == null) {
            return null;
        }
        if (currentContext != null && !currentContext.equals(section.storedContext)) {
            // Recorded under a different run context (project name or module override): the
            // annotations may be identical, but the rendered output would not be. Report no
            // fingerprint so the caller regenerates rather than short-circuits.
            return null;
        }
        return section.fingerprint;
    }

    /**
     * Binds the module id this compilation records its run headers under.
     *
     * <p>Called once the compiling module is known, which is after {@link #bindContext}: the
     * context is read in {@code init()}, the module identity only once a round has shown the
     * processor its sources. A module with no section of its own adopts the whole-root section a
     * pre-version-3 cache carries, so an upgrade costs no extra round for a single-module project
     * and one for every module but the first in a reactor.
     */
    public synchronized void bindModule(String moduleId) {
        loadIfNeeded();
        this.currentModule = moduleId;
        if (!sections.containsKey(moduleId)) {
            Section legacy = sections.remove(LEGACY_SECTION);
            if (legacy != null) {
                sections.put(moduleId, legacy);
                dirty = true;
            }
        }
        Section section = sections.get(moduleId);
        if (currentContext != null && (section == null || !currentContext.equals(section.storedContext))) {
            dirty = true; // the header must converge on this run's context; see bindContext
        }
    }

    private Section section() {
        return sections.computeIfAbsent(currentModule, id -> new Section());
    }

    /**
     * Records the current run's top-level build fingerprint. Call this after a successful
     * generate-and-write phase; the value is persisted on the next {@link #flush()}.
     */
    public synchronized void setBuildFingerprint(String fingerprint) {
        loadIfNeeded();
        Section section = section();
        if (!java.util.Objects.equals(section.fingerprint, fingerprint)) {
            section.fingerprint = fingerprint;
            this.dirty = true;
        }
    }

    /**
     * Returns the persisted sidecar stamp from the previous run, or {@code null} if not on file
     * or if the sidecar set has changed.
     *
     * <p>Both the root-wide sidecar stamp and this module's own view of it must match:
     * <ul>
     *   <li>If the root-wide stamp differs from the disk stamp, some module's sidecar was modified
     *       or deleted externally (e.g. retiring an emptied module; see {@code GuardrailLifecycleEndToEndTest}).</li>
     *   <li>If the root-wide stamp differs from this module's section stamp, another module in the
     *       reactor has compiled and updated the sidecar set since this module last ran, so this
     *       module must not short-circuit before catching up (see {@code MultiModuleCaseCollidingStemTest}).</li>
     * </ul>
     */
    public synchronized @Nullable String getSidecarStamp() {
        loadIfNeeded();
        if (!LEGACY_SECTION.equals(currentModule)) {
            Section section = sections.get(currentModule);
            if (section == null) {
                return null;
            }
            if (!java.util.Objects.equals(this.sidecarStamp, section.sidecarStamp)) {
                return null;
            }
            return section.sidecarStamp;
        }
        return sidecarStamp;
    }

    /** Records the current sidecar stamp; persisted on the next {@link #flush()}. */
    public synchronized void setSidecarStamp(String stamp) {
        loadIfNeeded();
        if (!java.util.Objects.equals(this.sidecarStamp, stamp)) {
            this.sidecarStamp = stamp;
            this.dirty = true;
        }
        Section section = section();
        if (!java.util.Objects.equals(section.sidecarStamp, stamp)) {
            section.sidecarStamp = stamp;
            this.dirty = true;
        }
    }

    /**
     * Binds the run context this compilation renders under: a stable hash of the option values
     * that shape generated output without being part of the annotation fingerprint —
     * {@code -Avibetags.project} (the llms.txt H1) and {@code -Avibetags.module} (the region a
     * reactor merge files this module under). {@link #getBuildFingerprint()} hides the stored
     * fingerprint when the context it was recorded under differs, so an option edit regenerates
     * instead of short-circuiting past it; an instance that never binds (tests patching the cache
     * directly) keeps and re-persists whatever context is already on file.
     */
    public synchronized void bindContext(String context) {
        loadIfNeeded();
        this.currentContext = context;
        Section section = sections.get(currentModule);
        if (section == null || !context.equals(section.storedContext)) {
            // The persisted header must converge on the new context even when nothing else
            // changes this run — otherwise the same mismatch re-fires on every later compile.
            this.dirty = true;
        }
    }

    /**
     * Returns true iff every entry currently in the cache points at a file whose size and mtime
     * still match what we recorded. Used by the top-level fingerprint short-circuit to confirm
     * that the on-disk state hasn't drifted (manual deletions, IDE rewrites, etc.) since we last
     * generated, before skipping a full build.
     *
     * <p>An empty cache returns {@code true} — there is no on-disk state to invalidate, so the
     * caller is free to fall through to the normal generate path on its own merits.
     */
    public synchronized boolean allCachedFilesStable() {
        loadIfNeeded();
        if (entries.isEmpty()) return true;
        for (java.util.Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> e = it.next();
            try {
                Path fullPath = rootDir.resolve(e.getKey()).normalize();
                BasicFileAttributes attrs = Files.readAttributes(fullPath, BasicFileAttributes.class);
                if (attrs.size() != e.getValue().size) return false;
                if (attrs.lastModifiedTime().toMillis() != e.getValue().mtime) return false;
            } catch (IOException ioe) {
                // A missing *output* stays in the cache: it must keep forcing regeneration until we
                // rewrite it. A missing *input* has no such rewrite — nothing will ever re-record it
                // — so drop it here, or its absence would suppress the short-circuit forever.
                if (INPUT_HASH.equals(e.getValue().hash)) {
                    it.remove();
                    dirty = true;
                }
                return false; // missing or unreadable — caller must regenerate
            }
        }
        return true;
    }

    /**
     * Records a config file that VibeTags <em>reads</em> rather than writes, so that editing it
     * invalidates {@link #allCachedFilesStable()} on the next compile.
     *
     * <p>Needed for inputs the build fingerprint cannot see — notably {@code .vibetags-mirror},
     * which lives in a module other than the one being compiled (see
     * {@code GuardrailFileWriter.watchInput}). The entry carries {@link #INPUT_HASH} instead of a
     * content hash: it is never a write target, so no {@link #isUnchanged} comparison can match it,
     * and {@link #allCachedFilesStable()} knows to prune it once the file goes away.
     */
    public synchronized void recordInput(Path file) {
        loadIfNeeded();
        String relKey = cacheKey(file);
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            Entry existing = entries.get(relKey);
            if (existing != null && existing.size == attrs.size()
                    && existing.mtime == attrs.lastModifiedTime().toMillis()
                    && INPUT_HASH.equals(existing.hash)) {
                return; // unchanged — do not dirty the cache for a file we only read
            }
            entries.put(relKey, new Entry(INPUT_HASH, attrs.size(), attrs.lastModifiedTime().toMillis()));
            dirty = true;
        } catch (IOException ignored) {
            if (entries.remove(relKey) != null) {
                dirty = true;
            }
        }
    }

    /**
     * Sentinel in the hash column marking a watched input rather than a file we wrote. Deliberately
     * not 8 hex digits, so it can never collide with a {@link #fingerprint} value.
     */
    static final String INPUT_HASH = "input---";

    /** Returns true iff cache says we wrote {@code body} to {@code file} and the file is byte-stable since. */
    @AIPerformance(constraint = "O(1): one stat(2) syscall plus one 8-char string compare; must not allocate byte[] — the prior CRC32C implementation did and was removed for this reason")
    public synchronized boolean isUnchanged(Path file, String body) {
        loadIfNeeded();
        String relKey = cacheKey(file);
        Entry e = entries.get(relKey);
        if (e == null) return false;
        try {
            // Single stat for both size and mtime — half the syscalls of two getXxx() calls.
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.size() != e.size) return false;
            // && short-circuits, so the hash is still only computed once size and mtime both match.
            return attrs.lastModifiedTime().toMillis() == e.mtime && e.hash.equals(fingerprint(body));
        } catch (IOException ioe) {
            return false; // file gone or unreadable — let the writer regenerate
        }
    }

    /** Records that {@code body} was written to {@code file}. One {@code readAttributes} call
     *  for both size and mtime; no per-call byte[] allocation. */
    public synchronized void recordWrite(Path file, String body) {
        loadIfNeeded();
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String relKey = cacheKey(file);
            entries.put(relKey,
                new Entry(fingerprint(body), attrs.size(), attrs.lastModifiedTime().toMillis()));
            dirty = true;
        } catch (IOException ignored) {
            // If we can't stat the file we just wrote, drop the cache entry rather than store stale data.
            String relKey = cacheKey(file);
            entries.remove(relKey);
            dirty = true;
        }
    }

    /** Removes a cache entry (e.g. when the writer skipped the file or the path is no longer ours). */
    public synchronized void invalidate(Path file) {
        loadIfNeeded();
        String relKey = cacheKey(file);
        if (entries.remove(relKey) != null) {
            dirty = true;
        }
    }

    /** Persists the cache to disk if anything changed. No-op when nothing was recorded. */
    public synchronized void flush() {
        if (!dirty) return;
        StringBuilder sb = new StringBuilder(64 + 128 * entries.size());
        sb.append("# VibeTags write cache. Auto-generated. Safe to delete.\n")
            .append("# format: ").append(FORMAT_VERSION).append('\n');
        if (sidecarStamp != null) {
            sb.append("# sidecar-stamp: ").append(sidecarStamp).append('\n');
        }
        // One header block per module. The legacy (unbound) section, when present, is written
        // first and without a "# module:" line, which is exactly the shape version 2 read.
        Section legacy = sections.get(LEGACY_SECTION);
        if (legacy != null) {
            appendHeaders(sb, LEGACY_SECTION, legacy);
        }
        for (Map.Entry<String, Section> section : sections.entrySet()) {
            if (!LEGACY_SECTION.equals(section.getKey())) {
                sb.append("# module: ").append(section.getKey()).append('\n');
                appendHeaders(sb, section.getKey(), section.getValue());
            }
        }
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            sb.append(e.getKey()).append('\t')
              .append(e.getValue().hash).append('\t')
              .append(e.getValue().size).append('\t')
              .append(e.getValue().mtime).append('\n');
        }
        try {
            // Store parent in a local so SpotBugs can track the null-check across the two uses.
            Path cacheParent = cachePath.getParent();
            if (cacheParent != null) {
                Files.createDirectories(cacheParent);
            }
            // Path.getFileName() returns null only for root paths — guard for correctness.
            Path cacheFileName = cachePath.getFileName();
            // Unique per writer: every module of a reactor flushes this one root-level cache, and a
            // fixed temp name lets the second writer truncate the first's bytes mid-move (#554).
            Path tmpDir = cacheParent != null ? cacheParent : cachePath.toAbsolutePath().getParent();
            if (tmpDir == null) {
                return; // a cache path that is a filesystem root is not a cache location
            }
            Path tmp = ModuleSidecar.uniqueTempFile(
                    tmpDir, cacheFileName != null ? cacheFileName.toString() : ".vibetags-cache");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            ModuleSidecar.moveIntoPlace(tmp, cachePath, ModuleSidecar.ATOMIC_REPLACE);
            dirty = false;
        } catch (IOException ignored) {
            // Cache flush is best-effort — losing it just means we rebuild on the next compile.
        }
    }

    /** The fingerprint, sidecar-stamp, and context lines of one section; the current module's context is the bound one. */
    private void appendHeaders(StringBuilder sb, String moduleId, Section section) {
        if (section.fingerprint != null) {
            sb.append("# fingerprint: ").append(section.fingerprint).append('\n');
        }
        if (!LEGACY_SECTION.equals(moduleId) && section.sidecarStamp != null) {
            sb.append("# sidecar-stamp: ").append(section.sidecarStamp).append('\n');
        }
        String effectiveContext = moduleId.equals(currentModule) && currentContext != null
            ? currentContext : section.storedContext;
        if (effectiveContext != null) {
            sb.append("# context: ").append(effectiveContext).append('\n');
        }
    }

    /** Visible for tests. */
    public synchronized int size() {
        loadIfNeeded();
        return entries.size();
    }

    private synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        // Headers before any "# module:" line belong to the whole-root section; registered only
        // once one is read, so a cache with nothing there leaves no empty section to adopt.
        Section legacy = new Section();
        Section loading = legacy;
        boolean readingLegacy = true;
        try {
            for (String line : Files.readAllLines(cachePath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    // Future-format guard: a cache written by a newer processor may use a line
                    // format this version cannot parse. Discard it wholesale — the cache is a
                    // pure optimisation and rebuilds on the next successful write.
                    String formatPrefix = "# format: ";
                    if (line.startsWith(formatPrefix)) {
                        try {
                            int version = Integer.parseInt(line.substring(formatPrefix.length()).trim());
                            if (version > FORMAT_VERSION) {
                                entries.clear();
                                sections.clear();
                                sidecarStamp = null;
                                return;
                            }
                        } catch (NumberFormatException ignored) {
                            // Unparseable format header — treat as unknown and start over.
                            entries.clear();
                            sections.clear();
                            sidecarStamp = null;
                            return;
                        }
                    }
                    // A "# module:" line opens that module's section; headers before any such
                    // line are the whole-root section a version-2 cache carries.
                    String modulePrefix = "# module: ";
                    if (line.startsWith(modulePrefix)) {
                        String id = line.substring(modulePrefix.length()).trim();
                        if (!id.isEmpty()) {
                            loading = sections.computeIfAbsent(id, k -> new Section());
                            readingLegacy = false;
                        }
                        continue;
                    }
                    // Recognise the fingerprint header; ignore other comments.
                    String prefix = "# fingerprint: ";
                    if (line.startsWith(prefix)) {
                        String fp = line.substring(prefix.length()).trim();
                        if (!fp.isEmpty()) {
                            loading.fingerprint = fp;
                            if (readingLegacy) sections.putIfAbsent(LEGACY_SECTION, legacy);
                        }
                    }
                    String sidecarPrefix = "# sidecar-stamp: ";
                    if (line.startsWith(sidecarPrefix)) {
                        String st = line.substring(sidecarPrefix.length()).trim();
                        if (!st.isEmpty()) {
                            if (readingLegacy) {
                                sidecarStamp = st;
                                legacy.sidecarStamp = st;
                            } else {
                                loading.sidecarStamp = st;
                            }
                        }
                    }
                    String contextPrefix = "# context: ";
                    if (line.startsWith(contextPrefix)) {
                        String cx = line.substring(contextPrefix.length()).trim();
                        if (!cx.isEmpty()) {
                            loading.storedContext = cx;
                            if (readingLegacy) sections.putIfAbsent(LEGACY_SECTION, legacy);
                        }
                    }
                    continue;
                }
                int t1 = line.indexOf('\t');
                if (t1 < 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 < 0) continue;
                int t3 = line.indexOf('\t', t2 + 1);
                if (t3 < 0) continue;
                String path = line.substring(0, t1);
                Path p = java.nio.file.Paths.get(path);
                if (p.isAbsolute()) {
                    try {
                        path = rootDir.relativize(p.normalize()).toString().replace('\\', '/');
                    } catch (IllegalArgumentException iae) {
                        path = p.normalize().toString().replace('\\', '/');
                    }
                }
                String hash = line.substring(t1 + 1, t2);
                try {
                    long size  = Long.parseLong(line.substring(t2 + 1, t3));
                    // trim() handles CRLF line endings on Windows: if the cache file was
                    // written or edited on Windows, the mtime value may carry a trailing \r
                    // that causes Long.parseLong to throw and silently drop a valid entry.
                    long mtime = Long.parseLong(line.substring(t3 + 1).trim());
                    entries.put(path, new Entry(hash, size, mtime));
                } catch (NumberFormatException ignored) {
                    // Skip corrupt rows — fresh entries replace them on next write.
                }
            }
        } catch (NoSuchFileException ignored) {
            // First run — empty cache is fine.
        } catch (IOException ioe) {
            entries.clear(); // Corrupt or unreadable — start over.
        }
    }

    /**
     * Computes a fingerprint of {@code s} using {@link String#hashCode()}, returned as 8 hex digits.
     *
     * <p>Why {@code String.hashCode()} and not a heavier hash:
     * <ul>
     *   <li>Same 32-bit collision space as CRC32C; for two non-adversarial VibeTags bodies the
     *       collision probability is 2^-32 ≈ 1 in 4 billion. A collision is a skipped <em>real</em>
     *       update: {@link #isUnchanged} compares the new body's hash against the recorded one, so
     *       colliding bodies make a changed file look current — the size+mtime checks guard only
     *       against external edits, not against the body changing. Accepted for non-adversarial
     *       input; see {@code ContentHash} for the same trade stated at the fingerprint level.</li>
     *   <li>{@link String} caches its {@code hashCode()} after first computation — subsequent calls
     *       on the same String reference are O(1). When the same body String is asked about
     *       multiple times in one compile, we pay O(N) once.</li>
     *   <li>HotSpot intrinsifies {@code String.hashCode()} on x86 with vectorised instructions.</li>
     *   <li>Crucially: <strong>no UTF-8 byte array materialisation per call</strong>. The previous
     *       CRC32C implementation allocated a fresh {@code byte[s.length()*?]} on every cache
     *       lookup, defeating the cache's purpose for large bodies.</li>
     * </ul>
     */
    private static String fingerprint(String s) {
        int h = s.hashCode();
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = HEX[h & 0xF];
            h >>>= 4;
        }
        return new String(out);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
