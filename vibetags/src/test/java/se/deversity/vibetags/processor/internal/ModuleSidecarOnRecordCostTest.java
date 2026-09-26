package se.deversity.vibetags.processor.internal;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two questions {@code init()} asks of the sidecars on record cost a constant per file, not a
 * share of what the files weigh (#858).
 *
 * <p>{@code AIGuardrailProcessor.init()} runs on every compilation, cold, warm or early-exited, and
 * asks two yes/no questions before any round: does some sidecar record annotated elements (#781),
 * and does one still hold a routed test round's unrouted body while {@code TESTING.md} is gone
 * (#782). Both answers decide {@code getSupportedAnnotationTypes()}. They were reached by parsing
 * every sidecar in full, and a sidecar carries every rendered body of its module, so after #834 that
 * parse was most of the 18.4 MB a no-op rebuild still allocated at N=1000.
 *
 * <p>Neither answer is in the bodies. This test pins the cost rather than the implementation, as
 * {@code ModuleSidecarUnreadableScanCostTest} does for the other per-build scan; {@code
 * ModuleSidecarOnRecordAgreementTest} is the other half, holding the cheap answers to the ones the
 * full parse reaches.
 */
class ModuleSidecarOnRecordCostTest {

    /** Enough body per sidecar that a per-byte cost cannot hide inside the fixed one. */
    private static final int BODY_CHARS = 256 * 1024;

    private static final int SIDECARS = 8;

    private static ThreadMXBean threadBean;

    @BeforeAll
    static void requireAllocationCounter() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean,
            "com.sun.management.ThreadMXBean not available on this JVM - cost not measured, not passed.");
        threadBean = (ThreadMXBean) platformBean;
        assumeTrue(threadBean.isThreadAllocatedMemoryEnabled(),
            "thread allocation counting is off - cost not measured, not passed.");
    }

    /**
     * Writes {@link #SIDECARS} sidecars shaped like a routed test round's: fat service bodies, a
     * handful of element ids, and an unrouted body that is all whitespace until its last character,
     * so deciding that it is not blank cannot be done by looking at its first few bytes.
     */
    private static long writeFatSidecars(Path root) throws IOException {
        StringBuilder body = new StringBuilder(BODY_CHARS);
        while (body.length() < BODY_CHARS) {
            body.append("guardrail body line ").append(body.length()).append('\n');
        }
        String lateUnrouted = " ".repeat(BODY_CHARS) + "x";
        long bytes = 0;
        for (int i = 0; i < SIDECARS; i++) {
            String moduleId = "mod" + i;
            Files.createDirectories(root.resolve(moduleId));
            ModuleSidecar sidecar = new ModuleSidecar(moduleId, moduleId);
            sidecar.putBody("claude", body.toString());
            sidecar.putBody("cursor", body.toString());
            sidecar.putUnroutedBody("claude", lateUnrouted);
            Set<String> ids = new LinkedHashSet<>();
            for (int e = 0; e < 5; e++) {
                ids.add("com.example." + moduleId + ".Type" + e);
            }
            sidecar.setElementIds(ids);
            sidecar.save(root);
            bytes += Files.size(root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId));
        }
        return bytes;
    }

    /** The smallest allocation across {@code rounds} calls, so a stray JIT event cannot inflate it. */
    private static long bestAllocationOf(BooleanSupplier question, int rounds) {
        long tid = Thread.currentThread().getId();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long before = threadBean.getThreadAllocatedBytes(tid);
            boolean answer = question.getAsBoolean();
            long allocated = threadBean.getThreadAllocatedBytes(tid) - before;
            assertTrue(answer, "every fixture sidecar records elements and a non-blank unrouted body");
            best = Math.min(best, allocated);
        }
        return best;
    }

    private static void assertCheap(String name, BooleanSupplier question, long onDisk) {
        bestAllocationOf(question, 3); // warm up the scan and the JIT before measuring
        long allocated = bestAllocationOf(question, 5);
        // The full parse allocated several times the bytes on disk: every line as a String, then
        // every value decoded. A tenth of the file size is far under that and far over anything a
        // fixed-size buffer reaches, so the threshold separates the two without pinning a design.
        long ceiling = onDisk / 10;
        assertTrue(allocated < ceiling,
            name + " allocated " + allocated + " bytes over " + onDisk
                + " bytes of sidecar; the answer is not in the bodies (ceiling " + ceiling + ")");
    }

    @Test
    @DisplayName("asking whether any sidecar records elements does not parse the bodies")
    void recordsElementsIsCheap(@TempDir Path root) throws IOException {
        long onDisk = writeFatSidecars(root);
        assertTrue(onDisk > 4L * 1024 * 1024,
            "fixture must be big enough for a per-byte cost to show: " + onDisk + " bytes");

        assertCheap("anyRecordsElements", () -> ModuleSidecar.anyRecordsElements(root), onDisk);
    }

    @Test
    @DisplayName("asking whether a withdrawn TESTING.md fallback is on record does not decode it whole")
    void withdrawnTestingFallbackIsCheap(@TempDir Path root) throws IOException {
        long onDisk = writeFatSidecars(root);

        assertCheap("holdsWithdrawnTestingFallback",
            () -> ModuleSidecar.holdsWithdrawnTestingFallback(root), onDisk);
    }
}
