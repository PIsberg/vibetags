package se.deversity.vibetags.processor.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replaces a file that other modules of a parallel reactor read and write at the same time.
 *
 * <p>The sidecars, the root {@code .vibetags-cache} and the root {@code .vibetags-baseline} are
 * each shared by every module of a reactor, so each is replaced the same way: the new bytes go to
 * a temp file whose name no other writer can produce, and the temp file is renamed over the target,
 * retrying while Windows refuses the rename because a sibling has the target open. A reader
 * therefore sees either the old file or the new one, never a torn one.
 *
 * <p>The generated guardrail files are not written here: {@code GuardrailFileWriter} stages them
 * with {@code Files.createTempFile} and has its own rules for a file that appears mid-write.
 */
final class AtomicFileReplacer {

    private AtomicFileReplacer() { }

    /**
     * Replaces {@code target} with {@code content}, UTF-8, through a temp file beside it.
     *
     * @throws IOException when the temp file cannot be written or the rename keeps failing; the
     *     target then still holds what it held before
     */
    static void replace(Path target, CharSequence content) throws IOException {
        replace(target, content, AtomicFileReplacer::writeUtf8, ATOMIC_REPLACE);
    }

    /** {@link #replace(Path, CharSequence)} with the write and the rename injectable, for tests. */
    static void replace(Path target, CharSequence content, StagedWrite stage, FileMover mover)
            throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path name = target.getFileName();
        if (dir == null || name == null) {
            throw new IOException("cannot replace a filesystem root: " + target);
        }
        Path tmp = uniqueTempFile(dir, name.toString());
        try {
            stage.write(tmp, content);
        } catch (IOException | RuntimeException failedWrite) {
            tryDelete(tmp); // half-written, and no reader would ever clean it up
            throw failedWrite;
        }
        moveIntoPlace(tmp, target, mover);
    }

    /** How the new content reaches the temp file. */
    @FunctionalInterface
    interface StagedWrite {
        void write(Path tmp, CharSequence content) throws IOException;
    }

    private static void writeUtf8(Path tmp, CharSequence content) throws IOException {
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
    }

    /**
     * Attempts at renaming the temp file over the live file before giving up.
     *
     * <p>Sized from measurement, not taste. Instrumenting {@link #moveIntoPlace} over five full
     * suite runs on Windows recorded ~1057 moves per run: about 985-1004 succeeded on the first
     * attempt, and the rest tailed off through attempts 2-9 — successes at attempt 8 and 9 were
     * observed in runs that passed. The previous schedule (10 attempts, 5 ms × attempt, 275 ms
     * total) sat directly on top of that tail, so two of the five runs exhausted it and failed.
     * A blocker is therefore either gone within ~15 ms or holds for far longer than 275 ms, which
     * is the signature of a virus scanner rather than of a sibling's {@link ModuleSidecar#readAll(Path)}.
     */
    static final int MOVE_ATTEMPTS = 15;
    /** First backoff step. Each attempt doubles it, up to {@link #MOVE_BACKOFF_MAX_MS}. */
    private static final long MOVE_BACKOFF_MS = 5L;
    /**
     * Ceiling for one backoff, so doubling stays bounded. The nominal schedule is
     * 5, 10, 20, 40, 80, 160 then 300 ms per attempt — about 2.7 s across all 15 attempts, and
     * at least half that once jitter is applied. Ten times the old budget, against a failure
     * whose cost is a module silently missing from the merged guardrails for that build.
     */
    private static final long MOVE_BACKOFF_MAX_MS = 300L;

    /**
     * How the temp file is renamed over the live file. Injectable so the retry path can be
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

    /** Distinguishes two temp files written by two threads of one copy of this class. */
    private static final AtomicLong TEMP_SEQUENCE = new AtomicLong();

    /**
     * Distinguishes two copies of this class in one JVM. A parallel reactor can load the processor
     * once per module, and each copy's {@link #TEMP_SEQUENCE} starts at zero, so pid and counter
     * alone repeat across them. Drawn once per class load; 60 random bits make a repeat negligible.
     */
    private static final String LOADER_TAG =
        Long.toHexString(java.util.UUID.randomUUID().getMostSignificantBits());

    /**
     * A temp-file path unique to this process and call: {@code <baseName>.<pid>-<tag>-<n>.tmp}.
     *
     * <p>A fixed {@code <baseName>.tmp} is the bug this replaces (#554). Every module of a parallel
     * reactor writes the root-level cache and baseline, and two of them sharing one temp name means
     * the second truncates the first's bytes: one rename moves content its writer never produced,
     * and the other fails outright. The pid separates processes, {@link #LOADER_TAG} separates the
     * copies of this class one process loads, and the counter separates calls.
     *
     * <p>Built by hand rather than with {@code Files.createTempFile} because that call is a
     * find-sec-bugs path-traversal sink when the directory comes from a processor option, and the
     * name still has to start with the caller's prefix and end in
     * {@code .tmp} — what {@code readAll}, the prune scan and the ignore files all filter on.
     */
    static Path uniqueTempFile(Path dir, String baseName) {
        return dir.resolve(baseName + "." + ProcessHandle.current().pid()
            + "-" + LOADER_TAG + "-" + TEMP_SEQUENCE.incrementAndGet() + ".tmp");
    }

    /**
     * Renames the freshly written temp file over {@code target}, retrying briefly before failing.
     *
     * <p>Windows refuses a rename onto a file that another process has open, and
     * {@link ModuleSidecar#readAll(Path)} in a sibling module's compilation opens exactly this file. In a
     * parallel reactor ({@code mvn -T}, {@code gradle --parallel}) that collision is reachable and
     * arrives as {@code AccessDeniedException} — which would abort this module's save and drop its
     * entire contribution from the merged output for that build, the failure the sidecar exists to
     * prevent. A sibling's read holds the handle for microseconds; a scanner's holds it for far
     * longer, which is why the schedule below backs off exponentially instead of trading a lost
     * module for a fixed handful of milliseconds. {@code ModuleSidecarAsyncTest} reproduces the
     * collision; without this retry it fails on Windows within a second.
     */
    static void moveIntoPlace(Path tmp, Path target, FileMover mover) throws IOException {
        moveIntoPlace(tmp, target, mover, Thread::sleep);
    }

    /**
     * The retry loop, with the wait injectable so the retry tests do not pay the real schedule.
     *
     * @param pause how to wait between attempts; production passes {@code Thread::sleep}
     */
    static void moveIntoPlace(Path tmp, Path target, FileMover mover, Backoff pause) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                mover.move(tmp, target);
                return;
            } catch (java.nio.file.FileSystemException e) {
                last = e;
                if (attempt == MOVE_ATTEMPTS) break;
                try {
                    pause.millis(jittered(backoffMillis(attempt)));
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

    /** How {@link #moveIntoPlace} waits between attempts. */
    @FunctionalInterface
    interface Backoff {
        void millis(long duration) throws InterruptedException;
    }

    /**
     * The nominal wait before attempt {@code attempt + 1}: {@link #MOVE_BACKOFF_MS} doubled per
     * attempt and capped at {@link #MOVE_BACKOFF_MAX_MS}. Deterministic, so the budget it adds up
     * to can be asserted; {@link #jittered} is what actually varies the sleep.
     */
    static long backoffMillis(int attempt) {
        int doublings = Math.min(attempt - 1, Long.SIZE - 2);
        return Math.min(MOVE_BACKOFF_MAX_MS, MOVE_BACKOFF_MS << doublings);
    }

    /**
     * Spreads a wait over {@code [duration/2, duration]}.
     *
     * <p>Every module in a reactor writes its sidecar at the same point in its own compile, so
     * without jitter their retries line up on one grid and keep colliding with the same reader
     * sweep — the retries then re-synchronise rather than spread out.
     */
    private static long jittered(long duration) {
        return duration / 2 + ThreadLocalRandom.current().nextLong(duration / 2 + 1);
    }


    private static void tryDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // Housekeeping: a temp file that cannot be removed now is ignored by every reader,
            // and failing the build over it would be the larger bug.
        }
    }
}
