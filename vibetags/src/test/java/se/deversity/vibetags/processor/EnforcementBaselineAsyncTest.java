package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.processor.internal.EnforcementBaseline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress test for {@link EnforcementBaseline} under a parallel reactor
 * ({@code mvn -T}, {@code gradle --parallel}) recording with
 * {@code -Avibetags.baseline.update=true}: every enforcing module rewrites its own lines in one
 * shared root-level file from its own javac invocation.
 *
 * <p>Two failures, both of which reach the developer as somebody else's problem:
 * <ul>
 *   <li>a <em>lost sibling</em> — a module merges into the snapshot it loaded before the sibling
 *       wrote, the sibling's approvals vanish, and that sibling's next enforcing build reports
 *       every guarded element as unrecorded;</li>
 *   <li>a <em>failed move</em> — two writers sharing one fixed temp name, where the first rename
 *       takes the file and the second throws, which {@code GuardrailEnforcer} turns into a compile
 *       error on a build that changed nothing.</li>
 * </ul>
 * Both are prevented by {@code update()} re-reading under an exclusive lock and renaming a temp
 * file created unique per writer (issue #554).
 */
@Tag("e2e")
class EnforcementBaselineAsyncTest {

    /**
     * Hard-coded rather than read from {@code EnforcementBaseline.FILE_NAME} (package-private):
     * the file is committed and shared between independently compiled modules, so a test that pins
     * the name literally is the point.
     */
    private static final String BASELINE = ".vibetags-baseline";

    private static final String FAMILY = "AIContract";
    private static final Set<String> FAMILIES = Set.of(FAMILY);

    /** Elements per module: enough lines that a lost merge is visible, few enough to stay quick. */
    private static final int ELEMENTS = 5;

    /**
     * Every module that has completed at least one update. Its approvals must survive every later
     * writer, which is the property a stale merge breaks.
     */
    private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();

    private static Path root;

    @BeforeAll
    static void setUp() throws IOException {
        root = Files.createTempDirectory("vibetags-baseline-async");
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

    /** One record-and-verify pass is not enough: the workers must interleave a merge with a move. */
    private static final int CYCLES_PER_INVOCATION = 12;

    @AsyncTest(threads = 6, invocations = 5, timeoutMs = 120_000)
    void concurrentBaselineUpdatesKeepEverySiblingsApprovals() throws IOException {
        // One module per worker, all recording into one shared root — a parallel reactor's shape.
        String moduleId = "mod" + Thread.currentThread().threadId();

        for (int cycle = 0; cycle < CYCLES_PER_INVOCATION; cycle++) {
            EnforcementBaseline.load(root).update(root, moduleId, currentFor(moduleId));
            RECORDED.add(moduleId);

            EnforcementBaseline reread = EnforcementBaseline.load(root);
            assertTrue(Files.exists(root.resolve(BASELINE)), "the baseline itself went missing");
            for (String recorded : RECORDED) {
                assertEquals(approvedKeys(recorded), reread.approvedFor(recorded, FAMILIES),
                    () -> "module " + recorded + " recorded its approvals and a sibling's update "
                        + "erased them; its next enforcing build reports every guarded element as "
                        + "unrecorded");
            }
        }
    }

    /** What one module records: stable across cycles, so update stays idempotent per module. */
    private static Map<String, String> currentFor(String moduleId) {
        Map<String, String> current = new LinkedHashMap<>();
        for (int i = 0; i < ELEMENTS; i++) {
            current.put(EnforcementBaseline.familyAndPath(FAMILY, path(moduleId, i)),
                "charge(int):boolean");
        }
        return current;
    }

    private static Set<String> approvedKeys(String moduleId) {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < ELEMENTS; i++) {
            keys.add(FAMILY + "\t" + path(moduleId, i));
        }
        return keys;
    }

    private static String path(String moduleId, int index) {
        return "com.example." + moduleId + ".Ledger#charge" + index + "(int)";
    }
}
