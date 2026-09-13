package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .qwen/settings.json} is Qwen Code's project settings file, and it belongs to the user
 * (#650). VibeTags used to treat it as an implicit sidecar of {@code QWEN.md}: opting into
 * {@code QWEN.md} replaced the whole file with a fixed three-setting document on every compile,
 * erasing MCP servers, model choice and permissions with nothing in any log to say so.
 *
 * <p>Nothing in that document came from an annotation, and none of it was a setting Qwen Code reads:
 * its settings schema has no top-level {@code project} key, and {@code QWEN.md} loads by default
 * without any settings at all. So VibeTags no longer writes the file. These tests pin what a Qwen
 * user can observe: the file they wrote is byte-for-byte what they wrote, the file is never created
 * for them, and a copy an older VibeTags generated is left in place rather than deleted.
 */
@Tag("e2e")
class QwenSettingsUntouchedEndToEndTest {

    /** Shaped like a real project settings file: MCP servers, a model, tool permissions. */
    private static final String HAND_WRITTEN = """
        {
          "model": {
            "name": "qwen3-coder-plus",
            "sessionTokenLimit": 32000
          },
          "mcpServers": {
            "github": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-github"]
            }
          },
          "mcp": {
            "allowed": ["github"]
          },
          "permissions": {
            "allow": ["Bash(mvn test)"]
          },
          "context": {
            "fileName": ["QWEN.md", "AGENTS.md"]
          }
        }
        """;

    /** Exactly what VibeTags wrote into the file before #650. */
    private static final String PREVIOUSLY_GENERATED = """
        {
          "project": {
            "model": "qwen3-coder-plus",
            "mcp": {
              "enabled": true
            }
          }
        }
        """;

    private static final String LOCKED = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "Settlement contract with the bank")
        public class PaymentLedger {}
        """;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness qwenOptedIn(Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        Files.createFile(dir.resolve("QWEN.md"));
        h.addSource("com.example.PaymentLedger", LOCKED);
        return h;
    }

    private static void writeSettings(Path dir, String content) throws IOException {
        Path settings = dir.resolve(".qwen/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, content, StandardCharsets.UTF_8);
    }

    @Test
    void aHandWrittenSettingsFileIsByteForByteUnchangedAfterCompiling(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = qwenOptedIn(dir);
        writeSettings(dir, HAND_WRITTEN);

        h.compile();
        assertEquals(HAND_WRITTEN, h.readFile(".qwen/settings.json"),
            "opting into QWEN.md must not touch Qwen Code's own settings file");

        h.compile();
        assertEquals(HAND_WRITTEN, h.readFile(".qwen/settings.json"),
            "nor on the second compile, when the write cache is warm");

        assertTrue(h.readFile("QWEN.md").contains("PaymentLedger"),
            "QWEN.md itself is still generated; only the settings file is left alone");
    }

    @Test
    void theSettingsFileIsNeverCreated(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = qwenOptedIn(dir);

        h.compile();

        assertFalse(h.fileExists(".qwen/settings.json"),
            "QWEN.md is the opt-in; a settings file the user never created is not VibeTags' to create");
        assertTrue(h.readFile("QWEN.md").contains("PaymentLedger"), "QWEN.md is still generated");
    }

    @Test
    void aCopyAnOlderVibeTagsGeneratedIsKeptNotDeleted(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = qwenOptedIn(dir);
        writeSettings(dir, PREVIOUSLY_GENERATED);

        h.compile();

        assertEquals(PREVIOUSLY_GENERATED, h.readFile(".qwen/settings.json"),
            "VibeTags never deletes a file in the user's tree; removing the stale copy is the user's call");
    }
}
