package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.internal.WriteCache;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Devin Desktop, formerly Windsurf, caps a workspace rule file at 12,000 characters (issue #695), and
 * Antigravity truncates a rules file past 24,000 bytes (issues #701, #850).
 *
 * <p>docs.devin.ai, Memories &amp; Rules: "Workspace rule files are limited to 12,000 characters
 * each." antigravity.google/docs/rules: "Antigravity truncates any single rule file that exceeds
 * 24,000 bytes"; the page said 12,000 characters when #701 was written. Devin Desktop does not say
 * whether a longer file is cut or dropped, and either way the build
 * used to report success, so the guardrails past the cap went missing with nothing said. A role
 * grouping many elements, a long annotation text, or the always-on safety file of a project with
 * many safety annotations can each pass the cap.
 *
 * <p>The warning reads the files as the build leaves them, so it fires on the build that wrote
 * them, on a build the fingerprint short-circuit skips, and in check mode, and it names each file
 * with its length.
 */
@Tag("e2e")
class RuleFileLengthEndToEndTest {

    private static final int LIMIT = 12_000;
    private static final int ANTIGRAVITY_BYTES = 24_000;
    private static final String RULE = "/com-example-web-Big.md";

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    private static String draftSource(int instructionLength) {
        return "package com.example.web;\n"
            + "@se.deversity.vibetags.annotations.AIDraft(instructions = \"" + "a".repeat(instructionLength) + "\")\n"
            + "public class Big {}\n";
    }

    private static List<String> lengthWarnings(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .map(d -> d.getMessage(null))
            .filter(m -> m.contains("characters, over the 12000") || m.contains("bytes, over the 24000"))
            .toList();
    }

    private static boolean anyMentions(List<String> messages, String... fragments) {
        return messages.stream().anyMatch(m -> Stream.of(fragments).allMatch(m::contains));
    }

    @ParameterizedTest
    @ValueSource(strings = {".devin/rules", ".windsurf/rules"})
    void aRuleFileOverTheCapWarnsWithItsNameAndLength(String dir, @TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(dir));
        h.addSource("com.example.web.Big", draftSource(LIMIT + 500));

        List<String> warnings = lengthWarnings(h.compileReturningDiagnostics());

        int length = h.readFile(dir + RULE).length();
        assertTrue(length > LIMIT, "the fixture must be over the cap, was " + length);
        assertTrue(anyMentions(warnings, dir + RULE + " is " + length + " characters"),
            "the warning names the file and its length, got: " + warnings);
    }

    /**
     * Antigravity caps {@code .agents/rules/} at 24,000 bytes and truncates past it (#850, which
     * replaced #701's 12,000 characters when the vendor page changed). The warning gives the length in
     * bytes and names the tool whose cap it is; the Devin Desktop and Windsurf wording, and the
     * {@code .windsurfrules} remedy, do not apply there.
     */
    @Test
    void anAntigravityRuleOverTheByteCapWarnsInBytesAndNamesAntigravity(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".agents/rules"));
        h.addSource("com.example.web.Big", draftSource(ANTIGRAVITY_BYTES + 500));

        List<String> warnings = lengthWarnings(h.compileReturningDiagnostics());

        long bytes = Files.size(root.resolve(".agents/rules" + RULE));
        assertEquals(1, warnings.size(), "one warning, for the one oversized file: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.contains(".agents/rules" + RULE + " is " + bytes + " bytes"), warning);
        assertTrue(warning.contains("Antigravity"), warning);
        assertFalse(warning.contains("Devin") || warning.contains("Windsurf") || warning.contains(".windsurfrules"),
            warning);
    }

    /** A rule over 12,000 characters but within 24,000 bytes is one Antigravity reads whole. */
    @Test
    void anAntigravityRuleWithinTheByteCapIsSilent(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".agents/rules"));
        h.addSource("com.example.web.Big", draftSource(LIMIT + 500));

        List<String> warnings = lengthWarnings(h.compileReturningDiagnostics());

        assertTrue(h.readFile(".agents/rules" + RULE).length() > LIMIT, "the fixture must be over 12,000 characters");
        assertEquals(List.of(), warnings);
    }

    /**
     * The boundary, measured rather than assumed: a first build gives the overhead of the rule
     * around the annotation text, so the second writes a file of exactly the cap, which the docs
     * allow, and the third one character more.
     */
    @Test
    void aFileAtTheCapIsSilentAndOneCharacterMoreWarns(@TempDir Path root) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        h.addSource("com.example.web.Big", draftSource(1000));
        h.compile();
        int overhead = h.readFile(".devin/rules" + RULE).length() - 1000;
        VibeTagsLogger.shutdown();

        ProcessorTestHarness.awaitFilesystemTick(root);
        h.clearSources();
        h.addSource("com.example.web.Big", draftSource(LIMIT - overhead));
        List<String> atCap = lengthWarnings(h.compileReturningDiagnostics());
        assertEquals(LIMIT, h.readFile(".devin/rules" + RULE).length(), "the fixture must sit exactly at the cap");
        assertEquals(List.of(), atCap, "a file of exactly the cap is within it");
        VibeTagsLogger.shutdown();

        ProcessorTestHarness.awaitFilesystemTick(root);
        h.clearSources();
        h.addSource("com.example.web.Big", draftSource(LIMIT - overhead + 1));
        List<String> overCap = lengthWarnings(h.compileReturningDiagnostics());
        assertTrue(anyMentions(overCap, ".devin/rules" + RULE + " is " + (LIMIT + 1) + " characters"),
            "one character over the cap warns: " + overCap);
    }

    /** The always-on safety file carries every safety bucket, so it is the likeliest to pass the cap. */
    @Test
    void theSafetyFileIsMeasuredToo(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        h.addSource("com.example.web.Big", "package com.example.web;\n"
            + "@se.deversity.vibetags.annotations.AILocked(reason = \"" + "b".repeat(LIMIT + 500) + "\")\n"
            + "public class Big {}\n");

        List<String> warnings = lengthWarnings(h.compileReturningDiagnostics());

        int length = h.readFile(".devin/rules/+vibetags-safety.md").length();
        assertTrue(length > LIMIT, "the safety file must be over the cap, was " + length);
        assertTrue(anyMentions(warnings, ".devin/rules/+vibetags-safety.md is " + length + " characters",
                ".windsurfrules"),
            "the safety file is named, with the remedy that applies to it: " + warnings);
    }

    /**
     * A build whose inputs are unchanged skips generation. The oversized file is still on disk and
     * still loaded, so the warning must not go quiet on exactly the builds that change nothing.
     */
    @Test
    void aBuildTheFingerprintShortCircuitSkipsStillWarns(@TempDir Path root) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        h.addSource("com.example.web.Big", draftSource(LIMIT + 500));
        h.compile();
        VibeTagsLogger.shutdown();
        // No sidecar gives a stamp of 0, so a cached stamp of 0 matches. The stamp is kept per module
        // (issue #556), so it is set in the section the first build wrote.
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.filter(p -> p.getFileName().toString().startsWith(".vibetags-mod-")).toList()) {
                Files.delete(p);
            }
        }
        Path cachePath = root.resolve(".vibetags-cache");
        String module = Files.readAllLines(cachePath).stream()
            .filter(line -> line.startsWith("# module: ")).findFirst().orElseThrow()
            .substring("# module: ".length());
        WriteCache cache = new WriteCache(cachePath);
        cache.bindModule(module);
        cache.setSidecarStamp("0");
        cache.flush();

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage(null).contains("inputs unchanged since last run")),
            "the second build must actually have been short-circuited: "
                + diagnostics.stream().map(d -> d.getMessage(null)).toList());
        assertTrue(anyMentions(lengthWarnings(diagnostics), ".devin/rules" + RULE),
            "the skipped build still warns: " + lengthWarnings(diagnostics));
    }

    /** Check mode writes nothing and reads the committed files, which are what the tool loads. */
    @Test
    void checkModeWarnsAboutTheCommittedFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        h.addSource("com.example.web.Big", draftSource(LIMIT + 500));
        h.compile();
        VibeTagsLogger.shutdown();

        List<String> warnings = lengthWarnings(h.compileReturningDiagnostics("-Avibetags.check=true"));

        assertTrue(anyMentions(warnings, ".windsurf/rules" + RULE), "check mode warns too: " + warnings);
    }

    /**
     * Only files VibeTags writes into are measured: a hand-authored rule with no markers is left to
     * its author, and a directory whose tool documents no cap is not measured at all.
     */
    @Test
    void aHandAuthoredRuleAndAnUncappedDirectoryAreNotMeasured(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        Files.createDirectories(root.resolve(".cursor/rules"));
        Files.writeString(root.resolve(".devin/rules/team-style.md"), "c".repeat(LIMIT + 500) + "\n");
        h.addSource("com.example.web.Big", draftSource(LIMIT + 500));

        List<String> warnings = lengthWarnings(h.compileReturningDiagnostics());

        assertTrue(h.readFile(".cursor/rules/com-example-web-Big.mdc").length() > LIMIT);
        assertTrue(warnings.stream().noneMatch(m -> m.contains("team-style.md") || m.contains(".cursor/")),
            "neither the hand-authored rule nor the Cursor file is measured: " + warnings);
        assertTrue(anyMentions(warnings, ".devin/rules" + RULE),
            "the generated file beside them still is: " + warnings);
    }
}
