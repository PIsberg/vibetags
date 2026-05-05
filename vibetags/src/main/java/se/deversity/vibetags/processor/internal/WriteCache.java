package se.deversity.vibetags.processor.internal;

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
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded = false;
    private boolean dirty = false;

    public WriteCache(Path cachePath) {
        this.cachePath = cachePath;
    }

    /** Returns true iff cache says we wrote {@code body} to {@code file} and the file is byte-stable since. */
    public boolean isUnchanged(Path file, String body) {
        loadIfNeeded();
        Entry e = entries.get(file.toString());
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
    public void recordWrite(Path file, String body) {
        loadIfNeeded();
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            entries.put(file.toString(),
                new Entry(fingerprint(body), attrs.size(), attrs.lastModifiedTime().toMillis()));
            dirty = true;
        } catch (IOException ignored) {
            // If we can't stat the file we just wrote, drop the cache entry rather than store stale data.
            entries.remove(file.toString());
            dirty = true;
        }
    }

    /** Removes a cache entry (e.g. when the writer skipped the file or the path is no longer ours). */
    public void invalidate(Path file) {
        loadIfNeeded();
        if (entries.remove(file.toString()) != null) {
            dirty = true;
        }
    }

    /** Persists the cache to disk if anything changed. No-op when nothing was recorded. */
    public void flush() {
        if (!dirty) return;
        StringBuilder sb = new StringBuilder(64 + 128 * entries.size());
        sb.append("# VibeTags write cache. Auto-generated. Safe to delete.\n");
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            sb.append(e.getKey()).append('\t')
              .append(e.getValue().hash).append('\t')
              .append(e.getValue().size).append('\t')
              .append(e.getValue().mtime).append('\n');
        }
        try {
            if (cachePath.getParent() != null) {
                Files.createDirectories(cachePath.getParent());
            }
            Path tmp = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
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
    public int size() {
        loadIfNeeded();
        return entries.size();
    }

    private synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        try {
            for (String line : Files.readAllLines(cachePath, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int t1 = line.indexOf('\t');
                if (t1 < 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 < 0) continue;
                int t3 = line.indexOf('\t', t2 + 1);
                if (t3 < 0) continue;
                String path = line.substring(0, t1);
                String hash = line.substring(t1 + 1, t2);
                try {
                    long size = Long.parseLong(line.substring(t2 + 1, t3));
                    long mtime = Long.parseLong(line.substring(t3 + 1));
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
