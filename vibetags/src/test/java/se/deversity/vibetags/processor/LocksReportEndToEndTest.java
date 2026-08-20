package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
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
@Tag("e2e")
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

    /**
     * {@code .vibetags-locks} is meant to be committed, so its content must not depend on where the
     * repository happens to sit on disk. An absolute {@code file} makes the report differ on every
     * machine and every CI runner, which is a permanent diff for anyone who commits it and the
     * reason a reactor cannot be gated on {@code git status --porcelain}.
     *
     * <p>The bundled Action already normalises absolute paths back to relative and matches on a
     * suffix either way, so this tightens what it receives rather than changing what it accepts.
     */
    @Test
    void locksReport_recordsPathsRelativeToTheRoot(@TempDir Path tmp) throws Exception {
        // A real file on disk, not an in-memory source: only a source javac resolves to a genuine
        // path can carry the checkout directory into the report.
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.writeSourceFile("src/main/java/com/example/vault/Vault.java",
            "package com.example.vault;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"audited\")\n"
                + "public class Vault {}\n");
        h.compile();

        String entry = jsonLines(h.readFile(".vibetags-locks")).stream()
            .filter(l -> l.contains("\"element\":\"com.example.vault.Vault\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Vault lock entry missing"));

        Matcher file = Pattern.compile("\"file\":\"([^\"]*)\"").matcher(entry);
        assertTrue(file.find(), "entry must carry a file path: " + entry);
        String path = file.group(1);

        assertFalse(path.contains(tmp.toAbsolutePath().toString().replace('\\', '/')),
            "the report must not bake in the checkout directory: " + path);
        assertFalse(path.startsWith("/") || path.matches("^[A-Za-z]:/.*"),
            "the file path must be relative to the VibeTags root: " + path);
        assertEquals("src/main/java/com/example/vault/Vault.java", path,
            "a relative path still has to locate the source");
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

        List<String> entries = jsonLines(h.readFile(".vibetags-locks"));
        assertEquals(List.of("{\"type\":\"format\",\"version\":1}"), entries,
            "no @AILocked elements means only the format record (plus header comments)");
    }


    /**
     * The withdrawal direction, which the rest of this class does not reach: an element is
     * unlocked and the report has to forget it. The report is a whole-file format with no marker
     * region per element, so it is rewritten wholesale each round — but it is also what the
     * locked-files Action diffs a pull request against, and a lock that outlives its annotation
     * fails PRs over code nobody is guarding any more.
     */
    @Test
    void locksReport_forgetsAnElementThatIsNoLongerLocked(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness first = new ProcessorTestHarness(tmp, false);
        first.touchOptIn(".vibetags-locks");
        first.addSource("com.example.Ledger",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"Reconciliation is load-bearing\")\n"
                + "public class Ledger {}\n");
        first.compile();
        VibeTagsLogger.shutdown();
        assertTrue(first.readFile(".vibetags-locks").contains("com.example.Ledger"),
            "precondition: the lock is reported while the annotation is there");

        ProcessorTestHarness.awaitFilesystemTick(tmp);
        ProcessorTestHarness second = new ProcessorTestHarness(tmp, false);
        second.touchOptIn(".vibetags-locks");
        second.addSource("com.example.Ledger",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"ledger\", avoids = \"nothing\")\n"
                + "public class Ledger {}\n");
        second.compile();

        String report = second.readFile(".vibetags-locks");
        assertFalse(report.contains("Reconciliation is load-bearing"),
            "the report must forget an element nothing locks any more: " + report);
        assertEquals(List.of("{\"type\":\"format\",\"version\":1}"),
            jsonLines(report),
            "and must be left with only the format record: " + report);
    }

    @Test
    void locksReport_startsWithFormatVersionRecord(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = withVaultSource(tmp);

        List<String> entries = jsonLines(h.readFile(".vibetags-locks"));
        assertFalse(entries.isEmpty(), "report must contain JSON records");
        assertEquals("{\"type\":\"format\",\"version\":1}", entries.get(0),
            "the first JSON record must declare the report's format version so consumers "
                + "can reject reports written in a future, incompatible schema");
        assertTrue(entries.stream().skip(1).allMatch(l -> l.contains("\"type\":\"locked\"")),
            "all records after the format record must be lock entries");
    }

    /**
     * Moving a locked element's lines, with every annotation unchanged, must still rewrite the
     * report.
     *
     * <p>The report records each locked element's line range, so a blank line above a locked class
     * changes its content. The fingerprint short-circuit skips the whole generate phase when the
     * annotation set and active services are unchanged, and positions were not part of it, so the
     * second build here rewrote nothing and left the committed report describing the old lines.
     *
     * <p>Measured on the repository itself before the fix: inserting a constant near the top of
     * {@code AIGuardrailProcessor} moved {@code generateFiles()} from 598-922 to 601-925,
     * {@code mvn compile -Pself-annotate} reported BUILD SUCCESS and rewrote nothing, and CI's
     * self-check then failed on that tree — with an error telling the developer to run the command
     * that had just done nothing. Deleting {@code .vibetags-cache} was the only way through.
     * Invariant 12: anything that becomes generated content reaches the fingerprint (issue #440).
     *
     * <p>Positions are resolved only when {@code .vibetags-locks} is opted in, so folding them in
     * costs projects without the report nothing.
     */
    @Test
    void locksReport_isRewrittenWhenALockedElementMovesWithNoAnnotationChange(@TempDir Path tmp)
            throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.lockdemo.Vault", vault(0));
        h.compile();

        String first = Files.readString(tmp.resolve(".vibetags-locks"), StandardCharsets.UTF_8);
        assertTrue(first.contains("\"startLine\":3"),
            "precondition: the lock is recorded at its original line. Report was:\n" + first);

        // Same annotations, same services, same everything except where the class sits.
        VibeTagsLogger.shutdown();
        ProcessorTestHarness moved = new ProcessorTestHarness(tmp);
        moved.addSource("com.example.lockdemo.Vault", vault(2));
        moved.compile();

        String second = Files.readString(tmp.resolve(".vibetags-locks"), StandardCharsets.UTF_8);
        assertTrue(second.contains("\"startLine\":5"),
            "the report must follow the element it describes. A stale line range makes the "
                + "locked-files PR guard diff against positions that no longer exist, and the "
                + "documented regeneration command cannot fix it because the short-circuit skips "
                + "the write. Report was:\n" + second);
    }

    /** The same class, shifted down by {@code padding} blank lines. */
    private static String vault(int padding) {
        return "package com.example.lockdemo;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "\n".repeat(padding)
            + "@AILocked(reason = \"audited\")\n"
            + "public class Vault {}\n";
    }

    private static ProcessorTestHarness withVaultSource(Path tmp) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.lockdemo.Vault",
            "package com.example.lockdemo;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"audited\")\n"
                + "public class Vault {}\n");
        h.compile();
        return h;
    }

    /** Extracts the non-comment JSON lines from the report (skips {@code #} marker/header lines). */
    private static List<String> jsonLines(String report) {
        return report.lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .collect(Collectors.toList());
    }
}
