package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIThreadSafe;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The committed record of what enforced elements looked like when they were last approved.
 *
 * <p>File: {@code <root>/.vibetags-baseline}, one tab-separated line per enforced element:
 * <pre>{@code
 * <moduleId>\t<family>\t<element path>\t<signature>
 * }</pre>
 *
 * <p>Signatures are stored in full rather than hashed, and the file is sorted, so a pull request
 * that changes a guarded API shows <em>what</em> changed in its own diff. A hash would make the file
 * smaller and the review worthless.
 *
 * <p><strong>Module ownership is part of the key</strong>, because every module of a reactor writes
 * to this one file from its own javac invocation. Updating rewrites only the lines belonging to the
 * compiling module and leaves its siblings' alone — the same merge discipline as the sidecars, and
 * for the same reason: without it the last module to compile would silently erase the rest
 * (issues #278, #330).
 */
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.SYNCHRONIZED,
    note = "update() alone is safe, and across processes as well as threads: a per-root monitor "
        + "plus an exclusive lock on .vibetags-baseline.lock serialise the re-read and rename that "
        + "a parallel reactor's modules run against one shared file. The read side is an unguarded "
        + "snapshot on purpose"
)
public final class EnforcementBaseline {

    static final String FILE_NAME = ".vibetags-baseline";
    /**
     * The inter-process mutex held across read-merge-write. Separate from the baseline itself
     * because the write replaces the baseline by rename, and Windows refuses to rename over a file
     * another process holds open — locking the target would trade a lost update for a failed move.
     * Empty, never read, and safe to delete between builds.
     */
    static final String LOCK_FILE_NAME = ".vibetags-baseline.lock";
    /**
     * One monitor per reactor root, because a {@link FileLock} is held by the whole JVM: two
     * threads of one Gradle daemon locking the same file get an
     * {@code OverlappingFileLockException} rather than mutual exclusion. Keyed by the normalised
     * root so unrelated roots never serialise.
     */
    private static final ConcurrentMap<String, Object> ROOT_MONITORS = new ConcurrentHashMap<>();
    private static final String HEADER =
        "# VibeTags enforcement baseline — regenerate with -Avibetags.baseline.update=true\n"
        + "# Lines are <moduleId>\\t<family>\\t<element>\\t<signature>, sorted; review changes here as\n"
        + "# you would any other API change.\n"
        + "# format: 1\n";
    private static final String FORMAT_MARKER = "# format: 1";

    /** key = moduleId + '\t' + family + '\t' + path, value = signature. */
    private final Map<String, String> entries;

    private EnforcementBaseline(Map<String, String> entries) {
        this.entries = entries;
    }

    /**
     * Reads the baseline at {@code root}, or an empty one when absent.
     *
     * <p>A file that is present but cannot be read or decoded throws instead of loading empty.
     * Loading it empty told the enforcer "nothing recorded", which it answers with a warning and a
     * pass; one Cp1252 byte from a Windows editor was enough to switch the gate off unnoticed.
     */
    public static EnforcementBaseline load(Path root) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        Path file = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new EnforcementBaseline(entries);
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int lastTab = line.lastIndexOf('\t');
            if (lastTab < 0) {
                continue;
            }
            entries.put(line.substring(0, lastTab), line.substring(lastTab + 1));
        }
        return new EnforcementBaseline(entries);
    }

    /**
     * True when the file exists and carries a format header this processor understands. Throws
     * when the file is there but cannot be read, for the same reason {@link #load} does.
     */
    public static boolean exists(Path root) throws IOException {
        Path file = root.resolve(FILE_NAME);
        return Files.isRegularFile(file)
            && Files.readAllLines(file, StandardCharsets.UTF_8).stream().anyMatch(FORMAT_MARKER::equals);
    }

    /** The approved signature for {@code path} under {@code family}, or {@code null} if unrecorded. */
    public @Nullable String signatureFor(String moduleId, String family, String path) {
        return entries.get(key(moduleId, family, path));
    }

    /** True when this baseline records nothing at all for {@code moduleId}. */
    public boolean hasNothingFor(String moduleId) {
        String prefix = moduleId + "\t";
        return entries.keySet().stream().noneMatch(k -> k.startsWith(prefix));
    }

    /**
     * Every {@code family\tpath} this baseline approved for {@code moduleId} under {@code families},
     * so the caller can spot approved elements that the compilation no longer contains.
     *
     * <p>That direction is the one that matters most: an element's path already encodes its
     * parameter types, so changing a method's signature does not edit an entry — it abandons one
     * and creates another. Checking only "did an entry's value change" would miss precisely the
     * breakage {@code @AIContract} exists to prevent.
     */
    public Set<String> approvedFor(String moduleId, Set<String> families) {
        String prefix = moduleId + "\t";
        Set<String> approved = new LinkedHashSet<>();
        for (String key : entries.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String familyAndPath = key.substring(prefix.length());
            int tab = familyAndPath.indexOf('\t');
            if (tab > 0 && families.contains(familyAndPath.substring(0, tab))) {
                approved.add(familyAndPath);
            }
        }
        return approved;
    }

    /**
     * Rewrites the baseline, replacing every line owned by {@code moduleId} with {@code current}
     * and preserving every sibling module's. Written atomically and sorted.
     *
     * <p>The merge re-reads the file under an exclusive lock instead of merging into whatever this
     * instance loaded. In a parallel reactor two enforcing modules record from separate javac
     * invocations against one shared root: without the lock both merge into the snapshot they read
     * before either wrote, the second rename wins, and the first module's approvals are gone — its
     * next enforcing build reports every guarded element as unrecorded (issue #554). Where the
     * filesystem refuses the lock the merge still re-reads, which is strictly better than merging a
     * snapshot from before the sibling wrote.
     *
     * @param current family + path → signature for the compiling module
     */
    public void update(Path root, String moduleId, Map<String, String> current) throws IOException {
        Object monitor = ROOT_MONITORS.computeIfAbsent(
            root.toAbsolutePath().normalize().toString(), key -> new Object());
        synchronized (monitor) {
            try (FileChannel channel = openLockFile(root)) {
                FileLock lock = acquire(channel);
                try {
                    updateLocked(root, moduleId, current);
                } finally {
                    release(lock);
                }
            }
        }
    }

    /**
     * The lock file's channel, or {@code null} when this filesystem will not give us one. A
     * read-only or exotic root must not fail a build that is only recording a baseline, so the
     * caller proceeds unlocked rather than throwing.
     */
    private static @Nullable FileChannel openLockFile(Path root) {
        try {
            return FileChannel.open(root.resolve(LOCK_FILE_NAME),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException unlockable) {
            return null;
        }
    }

    /** The exclusive lock, or {@code null} when it could not be taken. */
    private static @Nullable FileLock acquire(@Nullable FileChannel channel) {
        if (channel == null) {
            return null;
        }
        try {
            return channel.lock();
        } catch (IOException | RuntimeException unlockable) {
            // NFS and some container overlays refuse advisory locks outright, and a second lock on
            // the same file from this JVM arrives as OverlappingFileLockException.
            return null;
        }
    }

    private static void release(@Nullable FileLock lock) {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
        } catch (IOException ignored) {
            // The channel is closed immediately after, which releases it anyway.
        }
    }

    /** The read-merge-write itself, run with the lock held. */
    private void updateLocked(Path root, String moduleId, Map<String, String> current)
            throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(load(root).entries);
        String prefix = moduleId + "\t";
        merged.keySet().removeIf(k -> k.startsWith(prefix));
        current.forEach((familyAndPath, signature) -> merged.put(prefix + familyAndPath, signature));

        List<String> lines = new ArrayList<>(merged.size());
        merged.forEach((k, v) -> lines.add(k + "\t" + v));
        Collections.sort(lines);

        StringBuilder sb = new StringBuilder(HEADER);
        lines.forEach(line -> sb.append(line).append('\n'));

        // A temp name unique per writer: a fixed one is truncated by whichever sibling starts
        // second, so the first rename moves the other module's bytes into place and the second
        // fails with NoSuchFileException, which reaches the enforcer as a compile error.
        Path target = root.resolve(FILE_NAME);
        Path tmp = ModuleSidecar.uniqueTempFile(root, FILE_NAME);
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        ModuleSidecar.moveIntoPlace(tmp, target, ModuleSidecar.ATOMIC_REPLACE);
        entries.clear();
        entries.putAll(merged);
    }

    /** The composite key used in the file; also the shape {@link #update} expects, minus the module. */
    public static String familyAndPath(String family, String path) {
        return family + "\t" + path;
    }

    private static String key(String moduleId, String family, String path) {
        return moduleId + "\t" + familyAndPath(family, path);
    }
}
