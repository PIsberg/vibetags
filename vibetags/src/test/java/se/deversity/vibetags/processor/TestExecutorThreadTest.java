package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.ForkJoinWorkerThread;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * No test runs on a {@link ForkJoinWorkerThread} (#659).
 *
 * <p>The processor's write phase ends in {@code pool.submit(...).get()} on a pool of its own. When
 * the calling thread is a worker of <em>another</em> ForkJoinPool, {@code ForkJoinTask.get()} does
 * not park: it helps that thread's own pool by running queued tasks, and under JUnit's default
 * {@code fork_join_pool} executor every queued task is another test. A test then starts inside an
 * unrelated test's compilation, on the same thread, and that one can be stolen into in turn. One
 * {@code mvn test -Pe2e} run on a 16-core Windows host started 92 tests that way, nested up to 14
 * {@code generateFiles} frames deep with 808 frames on the stack before the nested test's own
 * compile began.
 *
 * <p>Enough nesting overflows the stack inside javac, and javac reports that by printing
 * "An exception has occurred in the compiler ... java.lang.StackOverflowError" to stderr and
 * returning {@code false} from {@code CompilationTask.call()} with no ERROR diagnostic. That is
 * both signatures #659 recorded on the Windows runner: the harness's "compilation reported failure
 * with no ERROR diagnostic", and surefire overflowing again while capturing the printed trace on the
 * same nearly-full stack.
 *
 * <p>{@code junit.jupiter.execution.parallel.config.executor-service = worker_thread_pool} runs tests
 * on plain threads, where the processor's {@code get()} parks and steals nothing. Repeated so the
 * check itself runs concurrently, on whatever threads the configured executor hands out.
 */
@DisplayName("Tests never run on a ForkJoinPool worker, so a compile's blocking join cannot start another test")
class TestExecutorThreadTest {

    @RepeatedTest(8)
    void theTestThreadIsNotAForkJoinWorker() {
        Thread thread = Thread.currentThread();
        assertFalse(thread instanceof ForkJoinWorkerThread,
            "test running on ForkJoinPool worker " + thread.getName() + ": the processor's blocking"
                + " ForkJoinTask.get() would run other queued tests inside this one's compilation"
                + " (#659). Set junit.jupiter.execution.parallel.config.executor-service ="
                + " worker_thread_pool in junit-platform.properties.");
    }
}
