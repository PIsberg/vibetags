package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module with nothing to exclude contributes nothing to {@code .aiexclude}, not a header.
 *
 * <p>{@code IgnoreFileRenderer} learned this in #328; {@code AiExcludeRenderer}, its twin, did not.
 * It always emitted its three-line header, and {@code ModuleSidecar.mergeFor} wraps every non-blank
 * contribution, so each module (and each source set: main and test are separate rounds) with no
 * excludable type added a header with no globs under it. This repository's own {@code .aiexclude}
 * held two empty copies; {@code examples/multimodule} held five headers around two real globs.
 *
 * <p>A member-level {@code @AILocked} is the case that makes "nothing to exclude" differ from "the
 * model is empty": the lock is real, but a glob cannot name a member, so it emits no line.
 */
@Tag("e2e")
class AiExcludeEmptyContributionEndToEndTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void memberOnlyLockWritesNoHeaderAndKeepsTheOptIn() throws IOException {
        ProcessorTestHarness h = optedIn();
        h.addSource("com.example.Registry", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            public class Registry {
                @AILocked(reason = "append only")
                public static final String ALL = "all";
            }
            """);
        h.compile();

        assertTrue(h.fileExists(".aiexclude"),
            "file presence is the opt-in (invariant 1); an empty contribution must not delete it");
        String content = h.readFile(".aiexclude");
        assertFalse(content.contains("strictly excluded"),
            "a module with no excludable type must not contribute a header. Was:\n" + content);
        assertTrue(h.readFile("GEMINI.md").contains("com.example.Registry.ALL"),
            "the member lock still reaches the platforms that can name a member");
    }

    @Test
    void lockedTypeStillWritesHeaderAndGlobOnce() throws IOException {
        ProcessorTestHarness h = optedIn();
        h.addSource("com.example.Payments", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "partner contract")
            public class Payments {}
            """);
        h.compile();

        String content = h.readFile(".aiexclude");
        assertTrue(content.contains("**/Payments.java"), "the locked type keeps its glob. Was:\n" + content);
        assertEquals(1, content.split("strictly excluded", -1).length - 1,
            "one contribution, one header. Was:\n" + content);
    }

    private ProcessorTestHarness optedIn() throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn(".aiexclude");
        h.touchOptIn("GEMINI.md"); // .aiexclude renders only beside GEMINI.md or codex
        return h;
    }
}
