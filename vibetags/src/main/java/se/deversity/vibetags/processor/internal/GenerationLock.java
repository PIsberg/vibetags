package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Serialises generation across the modules of one reactor root (#908).
 *
 * <p>A module round saves its sidecar, reads every sidecar, merges, and writes the root files. Two
 * modules doing that at once could interleave so that the one that read first wrote last, from a
 * merge without its sibling, and the sibling's region was gone from {@code CLAUDE.md} and every
 * other root file until the next build. Holding this lock across the whole of generation makes the
 * second module read after the first has written, so every merge sees every saved sidecar.
 *
 * <p>An exclusive {@link FileLock} on {@code .vibetags-generate.lock} at the root, taken with
 * {@link FileChannel#tryLock()} in a short poll rather than {@code lock()}. A file lock is held by
 * the whole JVM, so a second module of {@code mvn -T} or Gradle {@code --parallel} in the same
 * process gets {@link OverlappingFileLockException} instead of waiting, whichever classloader each
 * processor was loaded in. Polling covers both that and another process holding it.
 * {@code EnforcementBaseline} takes the same poll over its own lock file through
 * {@link #acquireFile}: with {@code lock()} it met that exception and recorded unlocked (#911).
 *
 * <p>Only one thread per JVM opens a channel on a lock file at a time; the others wait at an
 * in-JVM gate first (#923). On Linux the JDK takes {@code fcntl} locks, which belong to the
 * process, and closing <em>any</em> channel on the file drops all of them. A waiter that opened
 * its own channel and closed it on giving up freed the holder's lock for another process, while
 * the holder still reported it held. Each processor classloader has its own copy of this class, so
 * the gate cannot be a static field: it is an interned string's monitor, with a system property
 * naming the holder, the two pieces of state every classloader in the JVM shares.
 *
 * <p>Never fails a build. A root that will not give a channel, a filesystem that refuses locks, an
 * interrupt, or a wait past {@link #MAX_WAIT} all proceed unlocked, which is the behaviour before
 * this lock existed, and each says so in the log with its reason.
 */
public final class GenerationLock implements AutoCloseable {

    /** Empty, gitignored, never read: its lock is the only thing that matters. */
    public static final String FILE_NAME = ".vibetags-generate.lock";

    /**
     * How long a module waits for a sibling's generation. Generation is milliseconds to seconds;
     * a wait this long means a sibling is stuck, and proceeding unlocked beats hanging the build.
     */
    static final Duration MAX_WAIT = Duration.ofMinutes(2);

    private static final long POLL_MILLIS = 10;

    /** Prefix of the system property that marks a lock file's in-JVM gate as taken. */
    private static final String GATE_PROPERTY_PREFIX = "se.deversity.vibetags.lock-gate.";

    /** How {@link #enterGate} ended. */
    private enum Gate { ENTERED, ENTERED_AFTER_WAIT, TIMED_OUT }

    private static final GenerationLock UNLOCKED = new GenerationLock(null, null, null);

    private final @Nullable FileChannel channel;
    private final @Nullable FileLock lock;
    /** The gate this lock holds, left after the channel closes. */
    private final @Nullable String gate;

    private GenerationLock(@Nullable FileChannel channel, @Nullable FileLock lock, @Nullable String gate) {
        this.channel = channel;
        this.lock = lock;
        this.gate = gate;
    }

    /** No lock: for a round that cannot race a sibling, the root's own or a single module's. */
    public static GenerationLock none() {
        return UNLOCKED;
    }

    /** Takes the root's generation lock, waiting up to {@link #MAX_WAIT} for a sibling's. */
    public static GenerationLock acquire(Path root, String moduleId, @Nullable Logger log) {
        return acquire(root, moduleId, log, MAX_WAIT);
    }

    static GenerationLock acquire(Path root, String moduleId, @Nullable Logger log, Duration maxWait) {
        return acquireFile(root.resolve(FILE_NAME), moduleId, log, maxWait);
    }

    /**
     * The same poll over another lock file, for a second read-merge-write that sibling modules run
     * against one root file. {@code log} may be {@code null}; the events it gets are this class's
     * {@code generate.lock.*} ones, so a caller with a different contract passes none.
     */
    static GenerationLock acquireFile(Path lockFile, String moduleId, @Nullable Logger log, Duration maxWait) {
        long start = System.nanoTime();
        long deadline = start + maxWait.toNanos();
        String gate = GATE_PROPERTY_PREFIX + gateKey(lockFile);
        Gate entered;
        try {
            entered = enterGate(gate, moduleId, deadline, log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            skip(log, moduleId, "interrupted", "");
            return UNLOCKED;
        }
        if (entered == Gate.TIMED_OUT) {
            skip(log, moduleId, "timeout", "waitedMs=" + maxWait.toMillis());
            return UNLOCKED;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            leaveGate(gate);
            skip(log, moduleId, "unopenable", e.toString());
            return UNLOCKED;
        }
        boolean waited = entered == Gate.ENTERED_AFTER_WAIT;
        // Every close below is safe: holding the gate, no other thread in this JVM holds the lock.
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException heldInThisJvm) {
                // Past the gate, only a processor that predates it can hold the lock in this JVM.
                lock = null;
            } catch (IOException | RuntimeException e) {
                // NFS and some container overlays refuse advisory locks outright.
                closeQuietly(channel);
                leaveGate(gate);
                skip(log, moduleId, "unsupported", e.toString());
                return UNLOCKED;
            }
            if (lock != null) {
                if (waited && log != null) {
                    log.info("generate.lock.acquired module={} waitedMs={}", moduleId,
                        Duration.ofNanos(System.nanoTime() - start).toMillis());
                }
                return new GenerationLock(channel, lock, gate);
            }
            if (!waited && log != null) {
                log.info("generate.lock.wait module={} reason=sibling-generating", moduleId);
            }
            waited = true;
            if (System.nanoTime() >= deadline) {
                closeQuietly(channel);
                leaveGate(gate);
                skip(log, moduleId, "timeout", "waitedMs=" + maxWait.toMillis());
                return UNLOCKED;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeQuietly(channel);
                leaveGate(gate);
                skip(log, moduleId, "interrupted", "");
                return UNLOCKED;
            }
        }
    }

    /** Waits until no other thread in this JVM holds {@code gate}, then takes it, unless the deadline passes first. */
    private static Gate enterGate(String gate, String moduleId, long deadline, @Nullable Logger log)
            throws InterruptedException {
        Object monitor = gate.intern();
        boolean waited = false;
        synchronized (monitor) {
            while (System.getProperty(gate) != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return Gate.TIMED_OUT;
                }
                if (!waited && log != null) {
                    log.info("generate.lock.wait module={} reason=sibling-generating", moduleId);
                }
                waited = true;
                monitor.wait(Math.max(1, Duration.ofNanos(remaining).toMillis()));
            }
            System.setProperty(gate, moduleId);
        }
        return waited ? Gate.ENTERED_AFTER_WAIT : Gate.ENTERED;
    }

    private static void leaveGate(String gate) {
        Object monitor = gate.intern();
        synchronized (monitor) {
            System.clearProperty(gate);
            monitor.notifyAll();
        }
    }

    /** One key per lock file however a caller spells its root, so two spellings share one gate. */
    private static String gateKey(Path lockFile) {
        Path absolute = lockFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path name = absolute.getFileName();
        if (parent != null && name != null) {
            try {
                return parent.toRealPath().resolve(name.toString()).toString();
            } catch (IOException | RuntimeException e) {
                // A root that does not exist: its spelling is all there is to go on.
            }
        }
        return absolute.toString();
    }

    /** Whether this generation runs under the lock; false on every path that proceeds unlocked. */
    public boolean held() {
        return lock != null;
    }

    @Override
    public void close() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below releases it anyway.
            }
        }
        if (channel != null) {
            closeQuietly(channel);
        }
        if (gate != null) {
            leaveGate(gate);
        }
    }

    private static void skip(@Nullable Logger log, String moduleId, String reason, String detail) {
        if (log != null) {
            log.warn("generate.lock.skip module={} reason={} detail={}", moduleId, reason, detail);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing to do: the channel is being abandoned.
        }
    }
}
