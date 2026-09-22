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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Naming the sidecars a round could not read costs a constant, not a share of what they weigh.
 *
 * <p>{@code unreadableSidecarNames} runs on every build, from {@code generateFiles}, and its only
 * output is a list of file names - normally empty. It used to reach that answer by parsing every
 * sidecar on disk a second time: {@code readAllLines} materialised the whole file as strings and
 * every encoded value was base64-decoded to prove it was decodable, and both results were then
 * thrown away. A sidecar carries its module's entire collected model, so on a 1000-class module
 * that second pass allocated about 11.6 MB, which a release-to-release sweep saw as a 5.9 % step
 * in the processor's allocation overhead (issue #833).
 *
 * <p>The verdict it needs is not in the bodies. {@code FUTURE_VERSION} is the {@code # version}
 * header, {@code UNREADABLE} is either a file that would not open or one whose trailer is missing,
 * and every other outcome - corrupt, stale, healthy - goes unnamed alike. So this test pins the
 * cost rather than the implementation: whatever the scan does, it must not grow with the bytes it
 * scans past. {@code ModuleSidecarSkeletonLoadTest} is the other half, holding the cheap scan to
 * the same verdicts the full load reaches.
 */
class ModuleSidecarUnreadableScanCostTest {

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

    /** Writes {@link #SIDECARS} healthy sidecars, each carrying a body of {@link #BODY_CHARS}. */
    private static long writeFatSidecars(Path root) throws IOException {
        StringBuilder body = new StringBuilder(BODY_CHARS);
        while (body.length() < BODY_CHARS) {
            body.append("guardrail body line ").append(body.length()).append('\n');
        }
        long bytes = 0;
        for (int i = 0; i < SIDECARS; i++) {
            String moduleId = "mod" + i;
            Files.createDirectories(root.resolve(moduleId));
            ModuleSidecar sidecar = new ModuleSidecar(moduleId, moduleId);
            sidecar.putBody("claude", body.toString());
            sidecar.putBody("cursor", body.toString());
            sidecar.save(root);
            bytes += Files.size(root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId));
        }
        return bytes;
    }

    /** The smallest allocation across {@code rounds} calls, so a stray JIT event cannot inflate it. */
    private static long bestAllocationOf(Path root, int rounds) {
        long tid = Thread.currentThread().getId();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long before = threadBean.getThreadAllocatedBytes(tid);
            List<String> named = ModuleSidecar.unreadableSidecarNames(root);
            long allocated = threadBean.getThreadAllocatedBytes(tid) - before;
            assertTrue(named.isEmpty(), "fixture sidecars are healthy; nothing should be named");
            best = Math.min(best, allocated);
        }
        return best;
    }

    @Test
    @DisplayName("naming the unreadable sidecars does not allocate a share of what they weigh")
    void scanCostDoesNotTrackSidecarSize(@TempDir Path root) throws IOException {
        long onDisk = writeFatSidecars(root);
        assertTrue(onDisk > 4L * 1024 * 1024,
            "fixture must be big enough for a per-byte cost to show: " + onDisk + " bytes");

        bestAllocationOf(root, 3); // warm up the scan and the JIT before measuring
        long allocated = bestAllocationOf(root, 5);

        // Before #833 the scan allocated roughly 2.75x the bytes on disk (every line as a String,
        // then every encoded value decoded to prove it decodes). A tenth of the file size is far
        // under that and far over anything a fixed-size buffer can reach, so the threshold
        // separates the two without pinning an implementation.
        long ceiling = onDisk / 10;
        assertTrue(allocated < ceiling,
            "unreadableSidecarNames allocated " + allocated + " bytes scanning " + onDisk
                + " bytes of sidecar; the scan must not grow with the bodies it skips"
                + " (ceiling " + ceiling + ")");
    }

    @Test
    @DisplayName("the cost is flat: twice the sidecar bytes does not cost twice as much")
    void scanCostIsFlatInSidecarSize(@TempDir Path root) throws IOException {
        Path small = Files.createDirectories(root.resolve("small"));
        Path large = Files.createDirectories(root.resolve("large"));

        Files.createDirectories(small.resolve("one"));
        ModuleSidecar thin = new ModuleSidecar("one", "one");
        thin.putBody("claude", "tiny");
        thin.save(small);

        long largeBytes = writeFatSidecars(large);
        long smallBytes = Files.size(small.resolve(ModuleSidecar.SIDECAR_PREFIX + "one"));
        assertTrue(largeBytes > 100 * smallBytes, "the two fixtures must differ by an order of magnitude");

        bestAllocationOf(small, 3);
        bestAllocationOf(large, 3);
        long smallAlloc = bestAllocationOf(small, 5);
        long largeAlloc = bestAllocationOf(large, 5);

        assertEquals(0, ModuleSidecar.unreadableSidecarNames(large).size());
        // Per sidecar the scan pays for the file handle and its buffer; eight files cost more than
        // one. What it must not pay for is their content, so the gap between the two stays far
        // below the 4000x difference in bytes scanned.
        assertTrue(largeAlloc < 50 * smallAlloc,
            "scanning " + largeBytes + " bytes allocated " + largeAlloc + " against " + smallAlloc
                + " for " + smallBytes + " bytes: the cost is tracking the content");
    }
}
