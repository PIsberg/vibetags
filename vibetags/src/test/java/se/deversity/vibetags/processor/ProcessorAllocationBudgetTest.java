package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An operation-count budget for the processor's hot path, in the spirit of a performance
 * contract: not wall-clock (which is noise on shared runners; two identical runs of this
 * repo's JMH suite have differed by 1.93x), but allocated bytes, which
 * {@code load-tests/} measured stable to within 0.6% between runs at N=100/500/1000.
 *
 * <p>The budget covers a full in-memory compile of 100 annotated classes with one platform
 * opted in, measured via {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes} on
 * the compiling thread. That includes javac's own allocation (roughly 3/4 of the total per
 * the processor-tax measurements in {@code load-tests/results/}), and excludes the parallel
 * write phase's pool threads; both facts are part of the budget's definition, not errors in
 * it. The ceiling is deliberately about 3x the measured value so that JDK-version variance
 * (CI runs 21, 25 and 26) never trips it, while the failure it exists to catch - an
 * accidentally quadratic collector or a per-element re-render - blows through 3x immediately.
 *
 * <p>Measured 2026-08-15 on JDK 26 (Oracle, Windows): 264,955,400 bytes for this exact
 * fixture, observed by deliberately setting the budget to 1 byte and reading the failure.
 * Budget: 768 MB, 3.04x that measurement. If this fails, either the processor allocated an
 * order more than it used to (find out why before touching this number), or a JDK changed
 * javac's allocation profile by 3x (verify with {@code load-tests/}, then re-baseline the
 * ceiling in the same commit as the evidence).
 */
@Tag("e2e")
class ProcessorAllocationBudgetTest {

    private static final int CLASS_COUNT = 100;
    private static final long BUDGET_BYTES = 768L * 1024 * 1024;

    @TempDir
    static Path tempDir;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void compilingOneHundredAnnotatedClassesStaysInsideTheAllocationBudget() throws IOException {
        java.lang.management.ThreadMXBean plain = ManagementFactory.getThreadMXBean();
        assumeTrue(plain instanceof com.sun.management.ThreadMXBean,
            "JVM does not expose per-thread allocation; budget not measurable here");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) plain;
        assumeTrue(bean.isThreadAllocatedMemorySupported(),
            "per-thread allocation not supported; budget not measurable here");

        ProcessorTestHarness harness = new ProcessorTestHarness(tempDir, false);
        harness.touchOptIn("CLAUDE.md");
        for (int i = 0; i < CLASS_COUNT; i++) {
            harness.addSource("com.example.generated.Service" + i,
                "package com.example.generated;\n"
                    + "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "import se.deversity.vibetags.annotations.AIContract;\n"
                    + "public class Service" + i + " {\n"
                    + "    @AILocked(reason = \"budget fixture " + i + "\")\n"
                    + "    public void locked() { }\n"
                    + "    @AIContract(reason = \"budget fixture " + i + "\")\n"
                    + "    public int contract(int value) { return value; }\n"
                    + "}\n");
        }

        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        harness.compile();
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;

        assertTrue(allocated > 0, "allocation counter did not advance; measurement is broken");
        assertTrue(allocated < BUDGET_BYTES,
            "compiling " + CLASS_COUNT + " annotated classes allocated " + allocated
                + " bytes on the compiling thread, over the " + BUDGET_BYTES
                + "-byte budget. Read this test's javadoc before changing the number.");
    }
}
