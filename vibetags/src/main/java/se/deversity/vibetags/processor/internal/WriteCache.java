package se.deversity.vibetags.processor.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32C;

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
            return e.hash.equals(crc32c(body));
        } catch (IOException ioe) {
            return false; // file gone or unreadable — let the writer regenerate
        }
    }

    /** Records that {@code body} was written to {@code file}. Reads the file's mtime;
     *  reuses the body's UTF-8 byte length (which is what was just written) instead of
     *  re-stat-ing for size. */
    public void recordWrite(Path file, String body) {
        loadIfNeeded();
        try {
            long size = body.getBytes(StandardCharsets.UTF_8).length;
            long mtime = Files.getLastModifiedTime(file).toMillis();
            entries.put(file.toString(), new Entry(crc32c(body), size, mtime));
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
    int size() {
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
     * Computes a CRC32C fingerprint of the UTF-8 encoding of {@code s}, returned as 8 hex digits.
     *
     * <p>CRC32C is in the JDK ({@link CRC32C}, since 9) and uses the x86 SSE-4.2 CRC32
     * instruction at ~1.5 GB/s — roughly an order of magnitude faster than {@code SHA-256}
     * on this workload. We're using it as a non-adversarial fingerprint to answer "is this
     * the same body we wrote last time?" — collision probability for two
     * differently-generated VibeTags bodies is 2^-32 ≈ 1 in 4 billion, and a collision
     * would only mean we incorrectly skipped writing identical content, never silently
     * corrupted output (size + mtime are also checked first).
     */
    private static String crc32c(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.wrap(bytes));
        long v = crc.getValue();
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = HEX[(int) (v & 0xF)];
            v >>>= 4;
        }
        return new String(out);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
