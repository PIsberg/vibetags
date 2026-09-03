package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.processor.VibeTagsLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every sidecar this reader drops says why.
 *
 * <p>A module that disappears from the merged guardrails is the failure this class exists to
 * prevent, and it used to leave no trace at all: {@code ModuleSidecar} held no logger, so a pruned
 * or skipped sidecar was invisible in {@code vibetags.log} and the developer's only symptom was a
 * section missing from a generated file (issue #555). docs/LOGGING.md makes {@code reason=}
 * mandatory on any {@code .skip}; these are the reasons, and renaming one is a breaking change.
 *
 * <p>The distinction between the two events is the one a reader needs: {@code sidecar.prune} means
 * a file was deleted, {@code sidecar.skip} means it was left alone and only this build ignored it.
 */
@DisplayName("ModuleSidecar skip and prune events")
class ModuleSidecarLogContractTest {

    private static final String SIDECAR_PREFIX = ".vibetags-mod-";

    /** Sidecars are line-oriented; the fixture writes the same separator the reader splits on. */
    private static final String EOL = "\n";

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    /** The suite runs in parallel, so each test captures a logger named after itself. */
    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(
            ModuleSidecarLogContractTest.class.getName() + "." + testInfo.getDisplayName());
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
    @DisplayName("a sidecar written in an older format says stale-format when it is deleted")
    void staleFormatIsNamedOnPrune(@TempDir Path root) throws IOException {
        write(root, "old", "# version=1\nmoduleId=old\nmodulePath=\n# end\n");

        ModuleSidecar.readAll(root, logger);

        assertTrue(logged("sidecar.prune reason=stale-format path=" + SIDECAR_PREFIX + "old"),
            "an older processor's sidecar is deleted; without a reason it is indistinguishable "
                + "from corruption:\n" + dump());
        assertFalse(Files.exists(root.resolve(SIDECAR_PREFIX + "old")), "it really was deleted");
    }

    @Test
    @DisplayName("a corrupt sidecar says malformed rather than borrowing the stale reason")
    void malformedIsNamedOnPrune(@TempDir Path root) throws IOException {
        write(root, "bad", "# version=2\nno-module-id-here=x\n# end\n");

        ModuleSidecar.readAll(root, logger);

        assertTrue(logged("sidecar.prune reason=malformed path=" + SIDECAR_PREFIX + "bad"),
            "corruption and an old format are different facts about somebody's build:\n" + dump());
    }

    @Test
    @DisplayName("a sidecar from a newer processor is skipped, not pruned, and says so")
    void futureVersionIsSkippedNotPruned(@TempDir Path root) throws IOException {
        write(root, "newer", "# version=99\nmoduleId=newer\nmodulePath=\n# end\n");

        ModuleSidecar.readAll(root, logger);

        assertTrue(logged("sidecar.skip reason=future-version path=" + SIDECAR_PREFIX + "newer"),
            "a newer sibling's sidecar is skipped and kept; the event has to say skip:\n" + dump());
        assertFalse(logged("sidecar.prune"), "it must not be deleted, nor reported as deleted");
        assertTrue(Files.exists(root.resolve(SIDECAR_PREFIX + "newer")));
    }

    @Test
    @DisplayName("a module whose directory is gone says module-gone")
    void aDepartedModuleSaysSo(@TempDir Path root) throws IOException {
        write(root, "left", "# version=2\nmoduleId=left\nmodulePath=left\nregionId=left\n# end\n");

        ModuleSidecar.readAll(root, logger);

        assertTrue(logged("sidecar.prune reason=module-gone path=" + SIDECAR_PREFIX + "left"),
            "the module was removed from the build, which is not corruption:\n" + dump());
    }

    @Test
    @DisplayName("a module path this filesystem cannot spell is named separately")
    void anUnrepresentableModulePathSaysSo(@TempDir Path root) throws IOException {
        write(root, "nul", "# version=2\nmoduleId=nul\nmodulePath=core" + (char) 0
            + "x\nregionId=nul\n# end\n");

        ModuleSidecar.readAll(root, logger);

        assertTrue(logged("sidecar.prune reason=invalid-module-path path=" + SIDECAR_PREFIX + "nul"),
            "a module written on another OS is retired like a missing one, but it is a different "
                + "fact and the log is where that difference survives:\n" + dump());
    }

    @Test
    @DisplayName("check mode reports the same reasons as skips, because it deletes nothing")
    void peekAllReportsSkipsRatherThanPrunes(@TempDir Path root) throws IOException {
        write(root, "old", "# version=1\nmoduleId=old\nmodulePath=\n# end\n");

        ModuleSidecar.peekAll(root, logger);

        assertTrue(logged("sidecar.skip reason=stale-format path=" + SIDECAR_PREFIX + "old"),
            "check mode touches nothing it manages, so the event must not claim a deletion:\n"
                + dump());
        assertFalse(logged("sidecar.prune"), "check mode deleted nothing and must say nothing else");
        assertTrue(Files.exists(root.resolve(SIDECAR_PREFIX + "old")), "and the file is still there");
    }

    @Test
    @DisplayName("a healthy sidecar produces no skip or prune event at all")
    void aValidSidecarIsSilent(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        ModuleSidecar valid = new ModuleSidecar("core", "core");
        valid.putBody("claude", "## rules");
        valid.save(root);

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root, logger);

        assertFalse(logged("sidecar.skip"), "nothing was skipped:\n" + dump());
        assertFalse(logged("sidecar.prune"), "nothing was pruned:\n" + dump());
        assertTrue(loaded.size() == 1 && "core".equals(loaded.get(0).getModuleId()), dump());
    }

    private static void write(Path root, String moduleId, String content) throws IOException {
        Files.writeString(root.resolve(SIDECAR_PREFIX + moduleId), content, StandardCharsets.UTF_8);
    }

    private boolean logged(String needle) {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(needle));
    }

    private String dump() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
            .reduce("", (a, b) -> a + "\n  " + b);
    }

    @Test
    @DisplayName("the no-logger overload reports on the round's own log, and is silent without one")
    void theOverloadTheLockedCallSiteUsesStillReports(@TempDir Path root) throws IOException {
        // AIGuardrailProcessor.generateFiles() calls readAll(root) and is @AILocked, so the
        // logger cannot be threaded through it without editing locked code. It is resolved from
        // the round instead. This is the case that proves the main build path - the one that
        // actually prunes - is not the silent one.
        write(root, "old", String.join(EOL,
                        "# version=1", "moduleId=old", "modulePath=", "# end", ""));

        assertNull(VibeTagsLogger.currentFor(root), "nothing has configured this root yet");
        ModuleSidecar.readAll(root);
        assertFalse(Files.exists(root.resolve("vibetags.log")),
            "with no round in progress the read stays silent and creates no log");

        write(root, "old", String.join(EOL,
                        "# version=1", "moduleId=old", "modulePath=", "# end", ""));
        Logger roundLog = VibeTagsLogger.forRoot(root, null, "DEBUG");
        try {
            ModuleSidecar.readAll(root);
        } finally {
            VibeTagsLogger.shutdown(root);
        }

        assertNotNull(roundLog, "the round configured a logger");
        Path logFile = root.resolve("vibetags.log");
        assertTrue(Files.exists(logFile),
            "the round configured a logger and the read had a sidecar to prune, so the log "
                + "must exist; an absent file is the overload having stayed silent");
        String written = Files.readString(logFile);
        assertTrue(written.contains("sidecar.prune reason=stale-format"),
            "the locked call site's overload has to say why it deleted a module's sidecar, or "
                + "issue #555 is only fixed on the paths that were never the problem. Log: "
                + written);
    }
}
