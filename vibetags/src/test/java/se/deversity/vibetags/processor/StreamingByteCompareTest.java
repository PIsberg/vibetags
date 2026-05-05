package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GuardrailFileWriter#fileBytesEqual}, the streaming byte-by-byte
 * compare used by the non-marker overwrite fast path.
 */
class StreamingByteCompareTest {

    @Test
    void exactMatch_returnsTrue(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.txt");
        byte[] content = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);

        assertTrue(GuardrailFileWriter.fileBytesEqual(f, content));
    }

    @Test
    void firstByteDiffers_returnsFalse(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "Apple");
        byte[] expected = "Bpple".getBytes(StandardCharsets.UTF_8);

        assertFalse(GuardrailFileWriter.fileBytesEqual(f, expected),
            "early exit on first-byte mismatch must return false");
    }

    @Test
    void lastByteDiffers_returnsFalse(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "Apples");
        byte[] expected = "Applet".getBytes(StandardCharsets.UTF_8);

        assertFalse(GuardrailFileWriter.fileBytesEqual(f, expected),
            "mismatch at last byte must still return false");
    }

    @Test
    void emptyFile_emptyExpected_returnsTrue(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("empty.txt");
        Files.writeString(f, "");

        assertTrue(GuardrailFileWriter.fileBytesEqual(f, new byte[0]));
    }

    @Test
    void largeFile_exactMatch(@TempDir Path tmp) throws IOException {
        // 256 KB to exercise multiple buffer reads (8 KB internal buffer)
        byte[] content = new byte[256 * 1024];
        new Random(42).nextBytes(content);

        Path f = tmp.resolve("large.bin");
        Files.write(f, content);

        assertTrue(GuardrailFileWriter.fileBytesEqual(f, content));
    }

    @Test
    void largeFile_oneByteDiffersDeepInside(@TempDir Path tmp) throws IOException {
        byte[] content = new byte[64 * 1024];
        new Random(42).nextBytes(content);

        Path f = tmp.resolve("large.bin");
        Files.write(f, content);

        // Flip a bit deep in the file
        byte[] expected = content.clone();
        expected[40_000] ^= 1;

        assertFalse(GuardrailFileWriter.fileBytesEqual(f, expected));
    }

    @Test
    void unicodeContent_matchesByByteRepresentation(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("utf8.txt");
        // Multi-byte UTF-8: "héllo 🌍"
        String s = "héllo 🌍";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        Files.write(f, bytes);

        assertTrue(GuardrailFileWriter.fileBytesEqual(f, bytes));
        // Same string with one byte flipped
        byte[] flipped = bytes.clone();
        flipped[0] ^= 1;
        assertFalse(GuardrailFileWriter.fileBytesEqual(f, flipped));
    }

    @Test
    void fileExactlyAt8KBBufferBoundary_works(@TempDir Path tmp) throws IOException {
        // The internal buffer is 8 KB — exercise the boundary explicitly.
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i & 0xFF);

        Path f = tmp.resolve("boundary.bin");
        Files.write(f, content);

        assertTrue(GuardrailFileWriter.fileBytesEqual(f, content));
    }
}
