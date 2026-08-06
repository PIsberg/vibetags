package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #282: a regenerated file whose only difference from disk is line
 * endings (LF vs CRLF, e.g. after a git {@code core.autocrlf} checkout on Windows) must be a true
 * no-op — no rewrite, no CRLF→LF flip, no dirtied working tree.
 *
 * <p>VibeTags always emits LF, so the fix lives in the compare-before-write step: it normalises
 * line endings before deciding whether the content changed. When the on-disk file is byte-different
 * only because of CRLF, the writer leaves it untouched.
 */
class CrlfLineEndingIdempotenceTest {

    private static GuardrailFileWriter writer() {
        return new GuardrailFileWriter("# VibeTags\n", null, null, null);
    }

    /**
     * Marker file (CLAUDE.md) whose managed block is on disk with CRLF endings. The freshly
     * rendered LF body is identical modulo line endings → no write, and the file keeps its CRLF
     * bytes (proving it was not rewritten to LF).
     */
    @Test
    void markerFileWithCrlf_identicalModuloEol_isNotRewritten(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("CLAUDE.md");
        String crlf = "<!-- VIBETAGS-START -->\r\nsome generated rules\r\n<!-- VIBETAGS-END -->\r\n";
        Files.writeString(file, crlf, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        // The body the processor would render this round (LF, unwrapped) — the writer wraps it.
        boolean changed = writer().writeFileIfChanged(file.toString(), "some generated rules", true);

        assertFalse(changed, "CRLF-vs-LF-only difference must not count as a change");
        byte[] after = Files.readAllBytes(file);
        assertArrayEquals(before, after, "file must be left byte-for-byte untouched");
        assertTrue(new String(after, StandardCharsets.UTF_8).contains("\r\n"),
            "CRLF endings must be preserved, not flipped to LF");
    }

    /**
     * Non-marker file (JSON, full overwrite) with many CRLF lines so the on-disk byte size differs
     * from the LF render by far more than the old 64-byte size-mismatch tolerance. Must still be
     * recognised as unchanged and left alone.
     */
    @Test
    void nonMarkerJsonWithCrlf_largeFile_identicalModuloEol_isNotRewritten(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.json");
        StringBuilder body = new StringBuilder("{\n");
        for (int i = 0; i < 100; i++) {
            body.append("  \"key").append(i).append("\": ").append(i).append(",\n");
        }
        body.append("  \"last\": true\n}\n");
        String lf = body.toString();
        String crlf = lf.replace("\n", "\r\n");

        Files.writeString(file, crlf, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        assertTrue(before.length - lf.getBytes(StandardCharsets.UTF_8).length > 64,
            "sanity: CRLF size must exceed LF size by more than the old 64-byte tolerance");

        boolean changed = writer().writeFileIfChanged(file.toString(), lf, true);

        assertFalse(changed, "line-ending-only difference in a large non-marker file must be a no-op");
        assertArrayEquals(before, Files.readAllBytes(file), "file must be left byte-for-byte untouched");
    }

    /**
     * Guard against over-normalisation: a genuine content change (beyond line endings) must still
     * be written, even when the existing file uses CRLF.
     */
    @Test
    void markerFileWithCrlf_realContentChange_isRewritten(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("CLAUDE.md");
        Files.writeString(file, "<!-- VIBETAGS-START -->\r\nold rules\r\n<!-- VIBETAGS-END -->\r\n",
            StandardCharsets.UTF_8);

        boolean changed = writer().writeFileIfChanged(file.toString(), "brand new rules", true);

        assertTrue(changed, "a real content change must be written despite CRLF on disk");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("brand new rules"));
    }
}
