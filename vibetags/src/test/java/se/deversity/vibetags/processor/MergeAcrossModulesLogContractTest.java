package se.deversity.vibetags.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-module merge's DEBUG events are a contract, not commentary.
 *
 * <p>This method decides between two outcomes that the written file cannot tell apart: the
 * compiling module's own rendering, or the merged view of every module. Both are well-formed
 * documents, and the wrong one differs only by the siblings it is missing — which is why issue
 * #265 (JSON and TOML outputs never refreshing in a reactor) survived as long as it did. The
 * reason on each event is the only place the choice is observable, so the reasons are pinned here.
 *
 * <p>Renaming an event asserted here is a breaking change. See CLAUDE.md, "Logging".
 */
@DisplayName("mergeAcrossModules DEBUG events")
class MergeAcrossModulesLogContractTest {

    /** A marker-based service: merged through the sub-marker path. */
    private static final String MARKER_SERVICE = "claude";
    /** A marker-free service whose file is JSON, so it merges as a whole document. */
    private static final String JSON_SERVICE = "mentat";
    /** A marker-free service whose file is TOML. */
    private static final String TOML_SERVICE = "pr_agent";

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    /**
     * The suite runs in parallel, so a logger shared by name would mix one test's events into
     * another's assertions. Each test captures its own.
     */
    @BeforeEach
    void captureDebugLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(
            MergeAcrossModulesLogContractTest.class.getName() + "." + testInfo.getDisplayName());
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("a single-module build says why it merged nothing")
    void singleModuleSkipsWithAReason() {
        merge(MARKER_SERVICE, List.of(sidecar("alpha", MARKER_SERVICE, "rule from alpha")));

        assertTrue(logged("merge.skip reason=single-module"),
            "A single-module build takes an early return. Without a reason it is "
                + "indistinguishable from a reactor that merged and found nothing:\n" + dump());
        assertFalse(logged("merge.begin"), "nothing was merged, so no merge should be announced");
    }

    @Test
    @DisplayName("a marker-based service records that it merged through the markers")
    void markerServiceRecordsItsMerge() {
        merge(MARKER_SERVICE, List.of(
            sidecar("alpha", MARKER_SERVICE, "rule from alpha"),
            sidecar("beta", MARKER_SERVICE, "rule from beta")));

        assertTrue(logged("merge.begin modules=2"),
            "the reactor merge should announce its scale:\n" + dump());
        assertTrue(logged("merge.markers service=" + MARKER_SERVICE),
            "a marker-based merge should say so:\n" + dump());
    }

    @Test
    @DisplayName("a whole-file JSON merge records how many modules contributed")
    void jsonServiceRecordsContributionCount() {
        merge(JSON_SERVICE, List.of(
            sidecar("alpha", JSON_SERVICE, mentatDoc("com.example.Alpha")),
            sidecar("beta", JSON_SERVICE, mentatDoc("com.example.Beta"))));

        assertTrue(logged("merge.wholefile service=" + JSON_SERVICE + " contributions=2"),
            "contributions= is the count that answers \"did my sibling's rules make it in?\" — "
                + "the question issue #265 left unanswerable:\n" + dump());
    }

    @Test
    @DisplayName("a merger that declines an unexpected shape says it declined")
    void declinedMergeSaysSo() {
        // Not the document MentatRenderer emits, so JsonRulesMerge refuses to guess and returns
        // null. The file then ships this module's own rendering: valid, but blind to every
        // sibling. That is precisely the failure mode of #265, so it must never be silent.
        merge(JSON_SERVICE, List.of(
            sidecar("alpha", JSON_SERVICE, "not json at all"),
            sidecar("beta", JSON_SERVICE, "also not json")));

        assertTrue(logged("merge.skip service=" + JSON_SERVICE + " reason=merger-declined"),
            "A declined merge silently publishes one module's view. It is the single most "
                + "important thing this method can log:\n" + dump());
    }

    @Test
    @DisplayName("every skip carries a reason")
    void everySkipCarriesAReason() {
        // Exercise all three shapes so the assertion sees whatever skips they produce.
        merge(MARKER_SERVICE, List.of(sidecar("solo", MARKER_SERVICE, "rule")));
        merge(TOML_SERVICE, List.of(
            sidecar("alpha", TOML_SERVICE, "nonsense"),
            sidecar("beta", TOML_SERVICE, "nonsense")));
        merge(JSON_SERVICE, List.of(
            sidecar("alpha", JSON_SERVICE, mentatDoc("com.example.A")),
            sidecar("beta", JSON_SERVICE, mentatDoc("com.example.B"))));

        List<String> reasonless = events().stream()
            .filter(m -> m.contains(".skip"))
            .filter(m -> !m.contains("reason="))
            .toList();
        assertTrue(reasonless.isEmpty(),
            "A skip with no reason is the log line people actually need and the one that is "
                + "always missing (CLAUDE.md, \"Logging\"): " + reasonless);
        assertTrue(events().stream().anyMatch(m -> m.contains(".skip")),
            "this fixture should have produced at least one skip, or it is asserting "
                + "nothing:\n" + dump());
    }

    @Test
    @DisplayName("a disabled level formats nothing")
    void debugOffEmitsNothing() {
        logger.setLevel(Level.INFO);
        merge(JSON_SERVICE, List.of(
            sidecar("alpha", JSON_SERVICE, mentatDoc("com.example.A")),
            sidecar("beta", JSON_SERVICE, mentatDoc("com.example.B"))));

        assertTrue(events().isEmpty(),
            "The merge runs once per build per service; at a disabled level it must not format "
                + "an argument: " + events());
    }

    // -----------------------------------------------------------------------

    /** Drives the real production path, logger attached. */
    private void merge(String service, List<ModuleSidecar> sidecars) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(Path.of("."));
        String own = sidecars.get(0).getBodies().get(service);
        AIGuardrailProcessor.mergeAcrossModules(
            Map.of(service, own == null ? "" : own), serviceFiles, sidecars, logger);
    }

    /**
     * A {@code .mentatconfig.json} in the exact shape {@code MentatRenderer} emits, which is the
     * only one {@code JsonRulesMerge} accepts. Built from one template rather than hand-written
     * per test, so a fixture typo cannot masquerade as a declined merge.
     */
    private static String mentatDoc(String lockedPath) {
        return """
            {
              "_generated_by": "VibeTags",
              "rules": {
                "locked_files": [
                {"path": "%s"}
                ]
              }
            }
            """.formatted(lockedPath);
    }

    private static ModuleSidecar sidecar(String moduleId, String service, String body) {
        ModuleSidecar s = new ModuleSidecar(moduleId, moduleId);
        s.putBody(service, body);
        return s;
    }

    private List<String> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private boolean logged(String fragment) {
        return events().stream().anyMatch(m -> m.contains(fragment));
    }

    private String dump() {
        return String.join("\n  ", events());
    }
}
