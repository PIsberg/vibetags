package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build interrupted during its write phase (a Gradle daemon cancelling a compile interrupts the
 * compiling thread) must not leave a record that the next build trusts (#935).
 *
 * <p>{@code generateFiles()} waits for its parallel writes and, on an interrupt, restores the flag
 * and carries on to record the round's fingerprint. The suspicion was that the record would vouch
 * for files the round never wrote, so every later unchanged build would short-circuit past them. It
 * does not reproduce: the writes run on the pool's workers and complete anyway. This pins the end
 * state that matters, so a change that does let an interrupt cancel the writes has to keep it. The
 * interrupt is delivered through the {@code afterSidecarRead} seam, which runs on the compiling
 * thread just before that wait.
 */
@Tag("e2e")
@ResourceLock(ParallelReactorGenerationTest.SEAM_LOCK)
class InterruptedGenerationTest {

    private static final String THREAD = "interrupted-generation";

    @AfterEach
    void tearDown() {
        AIGuardrailProcessor.setAfterSidecarRead(null);
        VibeTagsLogger.shutdown();
    }

    @Test
    void anInterruptedGeneration_isRedoneByTheNextUnchangedBuild(@TempDir Path dir) throws Exception {
        compile(dir, "Balances are reconciled nightly");
        assertTrue(Files.readString(dir.resolve("CLAUDE.md")).contains("Balances are reconciled nightly"),
            "precondition: the first build writes the guardrail");
        ProcessorTestHarness.awaitFilesystemTick(dir);

        AIGuardrailProcessor.setAfterSidecarRead(() -> {
            if (THREAD.equals(Thread.currentThread().getName())) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            compile(dir, "Balances are reconciled hourly");
        } finally {
            AIGuardrailProcessor.setAfterSidecarRead(null);
        }
        // Not asserted here: what the interrupted build left. Measured when #935 was investigated,
        // the writes complete anyway, because they run on the pool's workers, which the interrupt
        // does not reach and shutdown() does not cancel; but a worker may still be finishing as the
        // compiling thread returns, so the file's state at this point is a race, not a contract.
        ProcessorTestHarness.awaitFilesystemTick(dir);

        compile(dir, "Balances are reconciled hourly");

        String claude = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("Balances are reconciled hourly"),
            "the build after an interrupted one must write what the interrupted one did not:\n" + claude);
    }

    /** One compile on a thread of its own name, so the seam can tell it from any other compile. */
    private static void compile(Path dir, String reason) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        Thread build = new Thread(() -> {
            try {
                ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
                h.touchOptIn("CLAUDE.md");
                h.addSource("com.example.Ledger", "package com.example;\n"
                    + "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "@AILocked(reason = \"" + reason + "\")\n"
                    + "public class Ledger {}\n");
                h.compile();
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                VibeTagsLogger.shutdown();
            }
        }, THREAD);
        build.start();
        build.join();
        assertTrue(failures.isEmpty(), () -> "compile failed: " + failures);
    }
}
