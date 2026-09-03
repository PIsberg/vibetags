package se.deversity.vibetags.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Argument handling in {@link Main}: exit code 2 is the documented usage error, and it has to
 * fire for every argument the CLI does not understand. Each case here was a silent success
 * before: a stray argument after {@code doctor} reported on the wrong directory, a misspelt
 * flag after {@code init} was ignored, and a missing {@code --dir} target surfaced as a
 * symlink warning.
 */
class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8), dir);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    void doctorRejectsAStrayArgumentInsteadOfReportingOnTheWrongDirectory() {
        assertEquals(2, run("doctor", "/some/other/project"));
        assertTrue(err().contains("doctor takes no arguments"), err());
        assertTrue(err().contains("--dir"), "the error names the option the user wanted: " + err());
        assertTrue(stdout.toString(StandardCharsets.UTF_8).isEmpty(), "no report was produced");
    }

    @Test
    void initRejectsAFlagItDoesNotUnderstand() throws Exception {
        assertEquals(2, run("init", "--platforms", "claude", "--bogus"));
        assertTrue(err().contains("--bogus"), err());
        try (Stream<Path> created = Files.list(dir)) {
            assertEquals(0, created.count(), "nothing is created on a usage error");
        }
    }

    @Test
    void initStillAcceptsItsOwnFlags() {
        assertEquals(0, run("init", "--list"));
        assertEquals(0, run("init", "--platforms", "claude"));
    }

    @Test
    void dirMustBeAnExistingDirectory() {
        Path missing = dir.resolve("does-not-exist");
        assertEquals(2, run("doctor", "--dir", missing.toString()));
        assertTrue(err().contains("not a directory"), err());
        assertTrue(err().contains(missing.getFileName().toString()), err());
    }

    @Test
    void consoleStreamsWriteUtf8RegardlessOfPlatformCharset() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        Main.utf8(raw).print("—");
        assertArrayEquals(new byte[] {(byte) 0xE2, (byte) 0x80, (byte) 0x94}, raw.toByteArray(),
            "an em-dash must leave as three UTF-8 bytes, not one Cp1252 byte");
    }
}
