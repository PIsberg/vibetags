package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the top-level build fingerprint short-circuit.
 *
 * <p>The processor records a fingerprint of (annotation set × active services) into
 * {@code .vibetags-cache}. On a subsequent compile, if the fingerprint still matches and every
 * cached output is byte-stable, the entire content-build + per-file-compare phase is skipped.
 *
 * <p>These tests verify three things:
 * <ol>
 *   <li>The fingerprint header lands in {@code .vibetags-cache} after a successful run.</li>
 *   <li>Recompiling identical sources keeps the fingerprint unchanged.</li>
 *   <li>Changing an annotation reason changes the fingerprint.</li>
 * </ol>
 */
class BuildFingerprintIntegrationTest {

    private static final Pattern FINGERPRINT_LINE = Pattern.compile("(?m)^# fingerprint: ([0-9a-f]{8})\\s*$");

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void firstCompile_writesFingerprintHeader(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness.withExampleSources(tmp);

        String cache = Files.readString(tmp.resolve(".vibetags-cache"), StandardCharsets.UTF_8);
        Matcher m = FINGERPRINT_LINE.matcher(cache);
        assertTrue(m.find(),
            "Expected '# fingerprint: <8 hex>' line in cache; got:\n" + cache);
    }

    @Test
    void identicalRecompile_keepsFingerprintStable(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness.withExampleSources(tmp);
        String fp1 = readFingerprint(tmp);

        // Recompile against the exact same source set — fingerprint must match.
        ProcessorTestHarness.withExampleSources(tmp);
        String fp2 = readFingerprint(tmp);

        assertEquals(fp1, fp2,
            "Fingerprint must be stable across compiles when inputs are identical");
    }

    @Test
    void changedAnnotationReason_changesFingerprint(@TempDir Path tmp) throws IOException {
        // First compile with a baseline reason on @AILocked.
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"original reason\")\n" +
            "public class A {}\n");
        h1.compile();
        String fp1 = readFingerprint(tmp);

        // Second compile, identical sources except the @AILocked.reason() string.
        // Wipe the cache so we know the second run actually re-ran generation.
        Files.deleteIfExists(tmp.resolve(".vibetags-cache"));

        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"completely different reason\")\n" +
            "public class A {}\n");
        h2.compile();
        String fp2 = readFingerprint(tmp);

        assertNotEquals(fp1, fp2,
            "Fingerprint must change when an annotation attribute value changes");
    }

    @Test
    void fingerprintIsEightHexChars(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness.withExampleSources(tmp);
        String fp = readFingerprint(tmp);
        assertNotNull(fp, "fingerprint header must be present");
        assertEquals(8, fp.length(),
            "fingerprint must be exactly 8 hex chars (matching WriteCache.fingerprint format)");
        assertTrue(fp.matches("[0-9a-f]{8}"),
            "fingerprint must be lowercase hex: " + fp);
    }

    private static String readFingerprint(Path root) throws IOException {
        String cache = Files.readString(root.resolve(".vibetags-cache"), StandardCharsets.UTF_8);
        Matcher m = FINGERPRINT_LINE.matcher(cache);
        if (!m.find()) {
            throw new AssertionError("No fingerprint line in cache:\n" + cache);
        }
        return m.group(1);
    }
}
