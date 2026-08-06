package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.processor.internal.ModuleSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress test for {@link ModuleSidecar} under a parallel reactor build
 * ({@code mvn -T}, {@code gradle --parallel}), where every module writes its own sidecar into
 * the shared root while reading every sibling's.
 *
 * <p>Two failures would be silent multi-module corruption, so both are asserted:
 * <ul>
 *   <li>a <em>torn read</em> — {@code readAll} observing a half-written sidecar and decoding a
 *       body that was never saved;</li>
 *   <li>a <em>wrongful prune</em> — {@code readAll} failing to parse a mid-write sidecar,
 *       classifying it as malformed and deleting a sibling module's contribution.</li>
 * </ul>
 * Both are prevented by {@code save()} writing to {@code .tmp} and atomically moving it into
 * place while {@code readAll()} skips {@code .tmp} files; this test is what keeps that pairing
 * honest.
 */
@Tag("e2e")
class ModuleSidecarAsyncTest {

    /**
     * Hard-coded rather than read from {@code ModuleSidecar.SIDECAR_PREFIX} (package-private):
     * the on-disk name is a cross-version contract between independently compiled modules, so a
     * test that pins it literally is the point, not an inconvenience.
     */
    private static final String SIDECAR_PREFIX = ".vibetags-mod-";

    private static final String SERVICE = "claude";

    private static Path root;

    @BeforeAll
    static void setUp() throws IOException {
        root = Files.createTempDirectory("vibetags-sidecar-async");
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (root == null) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a temp directory; a leftover file must not fail the test.
                }
            });
        }
    }

    /**
     * One save followed by one read is not enough to catch this: the workers arrive at their
     * saves together and finish before anyone reads, so the write window never overlaps a read.
     * The cycle below is what makes the race reachable — verified by giving {@code save()} a
     * non-atomic, chunked write to the live target, which this fails against and a single
     * save/read pass does not.
     *
     * <p>720 save/read pairs across 8 workers, which is roughly a quarter of what first reproduced
     * the Windows collisions and still overlaps writes and reads on every cycle. The two defects
     * this test found are pinned deterministically by {@code ModuleSidecarResilienceTest}; the job
     * left here is catching the next race, which does not need a minute of every build.
     */
    private static final int CYCLES_PER_INVOCATION = 15;

    @AsyncTest(threads = 8, invocations = 6, timeoutMs = 120_000)
    void concurrentSavesAndReadsNeverTearOrPruneASibling() throws IOException {
        // One module per worker, all sharing one reactor root — the shape of a parallel reactor.
        String moduleId = "mod" + Thread.currentThread().threadId();
        Files.createDirectories(root.resolve(moduleId));

        ModuleSidecar mine = new ModuleSidecar(moduleId, moduleId);
        mine.putBody(SERVICE, bodyFor(moduleId));

        for (int cycle = 0; cycle < CYCLES_PER_INVOCATION; cycle++) {
            mine.save(root);

            List<ModuleSidecar> all = ModuleSidecar.readAll(root);

            assertTrue(Files.exists(root.resolve(SIDECAR_PREFIX + moduleId)),
                () -> "a concurrent readAll() pruned " + moduleId + "'s sidecar as malformed");

            for (ModuleSidecar sidecar : all) {
                String id = sidecar.getModuleId();
                assertEquals(bodyFor(id), sidecar.getBodies().get(SERVICE),
                    () -> "sidecar " + id + " decoded to a body that was never saved");
                assertEquals(id, sidecar.getModulePath(),
                    () -> "sidecar " + id + " decoded a header field from another module");
            }
        }
    }

    /**
     * Deliberately long and module-specific: a torn read of a fixed-length body can decode to
     * something valid by accident, and a short one leaves no window for the tear to land in.
     */
    private static String bodyFor(String moduleId) {
        return ("## Guardrails for " + moduleId + "\n\n- " + moduleId + " line\n").repeat(200);
    }
}
