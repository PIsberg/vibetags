package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Isolated;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.BeforeEachInvocation;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderers are shared, and so are the formatters and tables behind them: one instance per
 * platform for the life of the JVM ({@link PlatformRendererRegistry}). A Gradle daemon compiles the
 * projects of a {@code --parallel} build in-process, so the same instances render for several
 * compilations at once. A renderer, formatter or section table that kept anything between calls
 * (a builder in a field, a lazily filled cache, a reused buffer) would mix one project's guardrails
 * into another's file, and a single-threaded test would never see it.
 *
 * <p>Every worker renders every aggregate platform for two models and compares each result with a
 * rendering made on one thread before the workers start. The two models are the fixtures that make
 * every per-annotation branch run, one with every member set and one with the optional members
 * unset, so the formatters' two sides of each ternary are both under load.
 *
 * <p>The granular half pins the claim in {@link GranularBody}'s javadoc: its memoized
 * {@code toString()} may be called from several threads, because recomputation is idempotent and
 * the field is volatile. Each invocation gets bodies nobody has rendered yet, so the workers race on
 * the first render rather than reading a cached string.
 */
// @Isolated: real platform threads plus detector instrumentation. Runs alone so that
// pressure does not reach the javac-based e2e tests beside it. See docs/TESTS.md.
@Isolated
class RendererConcurrencyAsyncTest {

    private static final RenderingContext CONTEXT = new RenderingContext(
        "Test Project", "# Generated Header\n",
        Set.of("llms", "llms_full", "pr_agent", "codex_config", "aider_conventions"));

    private static final List<Platform> PLATFORMS = Stream.of(Platform.values())
        .filter(p -> !p.name().endsWith("_GRANULAR"))
        .toList();

    private static final int INVOCATIONS_PER_RUN = 10;
    private static final AtomicInteger INVOCATIONS = new AtomicInteger();

    private static GuardrailModel everyMember;
    private static GuardrailModel optionalUnset;
    private static Map<String, String> expected;
    private static Map<String, String> expectedGranular;
    private static volatile Map<TaggedElement, GranularBody> freshGranular;

    @BeforeAll
    static void renderOnOneThread() {
        everyMember = GuardrailModels.everyAnnotation();
        optionalUnset = GuardrailModels.everyAnnotationWithOptionalMembersUnset();
        expected = new LinkedHashMap<>();
        for (Platform platform : PLATFORMS) {
            expected.put(key(platform, "every"), render(platform, everyMember));
            expected.put(key(platform, "unset"), render(platform, optionalUnset));
        }
        expectedGranular = byPath(PlatformRendererRegistry.granularRenderer().renderGranular(everyMember));
        assertTrue(expected.values().stream().anyMatch(Objects::nonNull),
            "precondition: the baseline rendered something");
        assertTrue(expectedGranular.size() > 10,
            "precondition: the granular baseline holds a body per annotated element");
    }

    @BeforeEachInvocation
    void unrenderedGranularBodies() {
        // Built here, on one thread, and never rendered: the workers' first toString() calls are
        // the race. A body taken from the baseline would only exercise the cached read.
        freshGranular = PlatformRendererRegistry.granularRenderer().renderGranular(everyMember);
        INVOCATIONS.incrementAndGet();
    }

    @AfterAll
    static void hookRan() {
        assertEquals(INVOCATIONS_PER_RUN, INVOCATIONS.get(),
            "@BeforeEachInvocation must run once per invocation, or workers share bodies across invocations "
                + "and race only on cached strings");
    }

    @AsyncTest(threads = 8, invocations = INVOCATIONS_PER_RUN, timeoutMs = 120_000,
        useVirtualThreads = false, preset = Preset.ALL,
        failOn = FailOn.HIGH, minTrust = TrustTier.FACT)
    void sharedRenderersProduceTheSingleThreadedOutput() {
        // Start each worker at a different platform so the same renderer is not always entered by
        // every worker at the same instant, and different renderers overlap as well.
        int offset = (int) (Thread.currentThread().threadId() % PLATFORMS.size());
        List<String> mismatched = new ArrayList<>();
        for (int i = 0; i < PLATFORMS.size(); i++) {
            Platform platform = PLATFORMS.get((i + offset) % PLATFORMS.size());
            check(mismatched, key(platform, "every"), render(platform, everyMember));
            check(mismatched, key(platform, "unset"), render(platform, optionalUnset));
        }
        Map<TaggedElement, GranularBody> bodies = freshGranular;
        assertTrue(bodies != null, "no granular bodies for this invocation");
        Map<String, String> granular = byPath(bodies);
        assertEquals(expectedGranular.keySet(), granular.keySet(), "granular bodies were not built per element");
        granular.forEach((path, body) -> check(mismatched, "granular " + path, body, expectedGranular.get(path)));
        assertTrue(mismatched.isEmpty(), () -> "rendered differently under concurrency: " + mismatched);
    }

    private static void check(List<String> mismatched, String key, String actual) {
        check(mismatched, key, actual, expected.get(key));
    }

    private static void check(List<String> mismatched, String key, String actual, String wanted) {
        if (!Objects.equals(wanted, actual)) {
            mismatched.add(key);
        }
    }

    private static String render(Platform platform, GuardrailModel model) {
        // TESTING.md renders only in a test round; see RendererDropsNoSupportedAnnotationTest.
        RenderingContext context = platform == Platform.TESTING ? CONTEXT.asTestRound() : CONTEXT;
        return PlatformRendererRegistry.getRenderer(platform).render(model, platform, context);
    }

    private static Map<String, String> byPath(Map<TaggedElement, GranularBody> bodies) {
        Map<String, String> byPath = new LinkedHashMap<>();
        bodies.forEach((element, body) -> byPath.put(element.path(), body.toString()));
        return byPath;
    }

    private static String key(Platform platform, String model) {
        return platform + "/" + model;
    }
}
