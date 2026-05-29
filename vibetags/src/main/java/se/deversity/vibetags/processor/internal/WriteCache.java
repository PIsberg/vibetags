package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIPerformance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
public final class WriteCache {

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
    private boolean loaded = false;
    private boolean dirty = false;

    /** Top-level build fingerprint (input-state hash). {@code null} when unknown. */
    private @Nullable String buildFingerprint = null;

    /** Combined mtime stamp of all module sidecar files (polynomial hash); detects cross-module changes. {@code null} when unknown. */
    private @Nullable String sidecarStamp = null;

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
     * input set plus the active service set — see {@link BuildFingerprint}.
     */
    public synchronized @Nullable String getBuildFingerprint() {
        loadIfNeeded();
        return buildFingerprint;
    }

    /**
     * Records the current run's top-level build fingerprint. Call this after a successful
     * generate-and-write phase; the value is persisted on the next {@link #flush()}.
     */
    public synchronized void setBuildFingerprint(String fingerprint) {
        loadIfNeeded();
        if (!java.util.Objects.equals(this.buildFingerprint, fingerprint)) {
            this.buildFingerprint = fingerprint;
            this.dirty = true;
        }
    }

    /**
     * Returns the persisted sidecar stamp from the previous run, or {@code null} if not on file.
     * The stamp is a hex-encoded polynomial hash of all module sidecar file mtimes; a change means a sibling
     * module's annotations changed and the aggregated output must be regenerated.
     */
    public synchronized @Nullable String getSidecarStamp() {
        loadIfNeeded();
        return sidecarStamp;
    }

    /** Records the current sidecar stamp; persisted on the next {@link #flush()}. */
    public synchronized void setSidecarStamp(String stamp) {
        loadIfNeeded();
        if (!java.util.Objects.equals(this.sidecarStamp, stamp)) {
            this.sidecarStamp = stamp;
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
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            try {
                Path fullPath = rootDir.resolve(e.getKey()).normalize();
                BasicFileAttributes attrs = Files.readAttributes(fullPath, BasicFileAttributes.class);
                if (attrs.size() != e.getValue().size) return false;
                if (attrs.lastModifiedTime().toMillis() != e.getValue().mtime) return false;
            } catch (IOException ioe) {
                return false; // missing or unreadable — caller must regenerate
            }
        }
        return true;
    }

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
            if (attrs.lastModifiedTime().toMillis() != e.mtime) return false;
            return e.hash.equals(fingerprint(body));
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
        sb.append("# VibeTags write cache. Auto-generated. Safe to delete.\n");
        if (buildFingerprint != null) {
            sb.append("# fingerprint: ").append(buildFingerprint).append('\n');
        }
        if (sidecarStamp != null) {
            sb.append("# sidecar-stamp: ").append(sidecarStamp).append('\n');
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
            Path tmp = cachePath.resolveSibling(
                    (cacheFileName != null ? cacheFileName.toString() : ".vibetags-cache") + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException ignored) {
            // Cache flush is best-effort — losing it just means we rebuild on the next compile.
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
        try {
            for (String line : Files.readAllLines(cachePath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    // Recognise the fingerprint header; ignore other comments.
                    String prefix = "# fingerprint: ";
                    if (line.startsWith(prefix)) {
                        String fp = line.substring(prefix.length()).trim();
                        if (!fp.isEmpty()) buildFingerprint = fp;
                    }
                    String sidecarPrefix = "# sidecar-stamp: ";
                    if (line.startsWith(sidecarPrefix)) {
                        String st = line.substring(sidecarPrefix.length()).trim();
                        if (!st.isEmpty()) sidecarStamp = st;
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
        } catch (NoSuchFileException nsfe) {
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
     *       collision probability is 2^-32 ≈ 1 in 4 billion, and a collision could only cause us
     *       to skip writing identical content (never silently corrupt output, since size + mtime
     *       are checked first).</li>
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
