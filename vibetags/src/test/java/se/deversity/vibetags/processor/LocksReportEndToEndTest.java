package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the machine-readable {@code .vibetags-locks} report (service key
 * {@code locks_report}): JSON Lines between hash markers, one entry per {@code @AILocked}
 * element with element path, kind, source file, line range, and reason. Consumed by the
 * locked-files GitHub Action for PR diff guarding.
 */
class LocksReportEndToEndTest {

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void locksReport_containsClassLevelLockWithPositions(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = ProcessorTestHarness.withExampleSources(tmp);

        String report = h.readFile(".vibetags-locks");
        assertTrue(report.contains("# VIBETAGS-START"), "report must use hash markers");
        assertTrue(report.contains("# VIBETAGS-END"), "report must use hash markers");

        List<String> entries = jsonLines(report);
        assertFalse(entries.isEmpty(), "report must contain at least one lock entry");

        String paymentEntry = entries.stream()
            .filter(l -> l.contains("\"element\":\"com.example.payment.PaymentProcessor\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("PaymentProcessor lock entry missing in: " + report));

        assertTrue(paymentEntry.contains("\"type\":\"locked\""), "entry must be typed");
        assertTrue(paymentEntry.contains("\"kind\":\"CLASS\""), "entry must carry the element kind");
        assertTrue(paymentEntry.contains("\"reason\":\"Core payment logic - do not refactor\""),
            "entry must carry the @AILocked reason");
        assertTrue(paymentEntry.contains("PaymentProcessor.java"),
            "entry must reference the source file: " + paymentEntry);

        Matcher m = Pattern.compile("\"startLine\":(\\d+),\"endLine\":(\\d+)").matcher(paymentEntry);
        assertTrue(m.find(), "entry must carry line positions under javac: " + paymentEntry);
        long startLine = Long.parseLong(m.group(1));
        long endLine = Long.parseLong(m.group(2));
        assertTrue(startLine >= 1, "startLine must be 1-based");
        assertTrue(endLine >= startLine, "endLine must not precede startLine");
    }

    @Test
    void locksReport_containsMethodLevelLock(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.lockdemo.Vault",
            "package com.example.lockdemo;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Vault {\n"
                + "    @AILocked(reason = \"audited open-sequence\")\n"
                + "    public void open() {\n"
                + "        // multi-line body so start and end lines differ\n"
                + "    }\n"
                + "}\n");
        h.compile();

        String report = h.readFile(".vibetags-locks");
        String entry = jsonLines(report).stream()
            .filter(l -> l.contains("\"element\":\"com.example.lockdemo.Vault.open()\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("method lock entry missing in: " + report));

        assertTrue(entry.contains("\"kind\":\"METHOD\""));
        assertTrue(entry.contains("\"reason\":\"audited open-sequence\""));

        Matcher m = Pattern.compile("\"startLine\":(\\d+),\"endLine\":(\\d+)").matcher(entry);
        assertTrue(m.find(), "method entry must carry line positions: " + entry);
        long startLine = Long.parseLong(m.group(1));
        long endLine = Long.parseLong(m.group(2));
        // Annotation on line 4, body ends on line 7 of the source above.
        assertEquals(4, startLine, "method range must start at the @AILocked annotation line");
        assertEquals(7, endLine, "method range must end at the closing brace");
    }

    @Test
    void locksReport_escapesJsonSpecialCharactersInReason(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.lockdemo.Quoted",
            "package com.example.lockdemo;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"keep \\\"as-is\\\" \\\\ verbatim\")\n"
                + "public class Quoted {}\n");
        h.compile();

        String entry = jsonLines(h.readFile(".vibetags-locks")).stream()
            .filter(l -> l.contains("Quoted"))
            .findFirst()
            .orElseThrow();
        assertTrue(entry.contains("\"reason\":\"keep \\\"as-is\\\" \\\\ verbatim\""),
            "quotes and backslashes must be JSON-escaped: " + entry);
    }

    @Test
    void locksReport_notCreatedWithoutOptIn(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        Files.deleteIfExists(tmp.resolve(".vibetags-locks"));
        h.addSource("com.example.A",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"r\")\n"
                + "public class A {}\n");
        h.compile();

        assertFalse(h.fileExists(".vibetags-locks"),
            "the report is opt-in by file existence and must never be created unbidden");
    }

    @Test
    void locksReport_emptyWhenNoLockedElements(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.B",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"f\", avoids = \"a\")\n"
                + "public class B {}\n");
        h.compile();

        assertTrue(jsonLines(h.readFile(".vibetags-locks")).isEmpty(),
            "no @AILocked elements means no JSON entries (header comments only)");
    }

    /** Extracts the non-comment JSON lines from the report (skips {@code #} marker/header lines). */
    private static List<String> jsonLines(String report) {
        return report.lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .collect(Collectors.toList());
    }
}
