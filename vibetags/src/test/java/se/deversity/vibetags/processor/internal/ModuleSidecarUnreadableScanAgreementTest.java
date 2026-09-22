package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cheap scan names exactly the sidecars the full load calls unreadable.
 *
 * <p>{@code unreadableSidecarNames} used to reach its verdict by parsing every sidecar a second
 * time, which is where the allocation in issue #833 came from. It now streams the file instead and
 * decodes nothing, on the reasoning that only three things can produce the two verdicts it reports
 * - the version header, whether the file reads as UTF-8 at all, and the trailer - while every
 * other outcome the full load can reach goes unnamed alike.
 *
 * <p>That reasoning is what this test makes falsifiable. Each case asserts the same relation
 * rather than a hand-copied expectation: the scan names a file if and only if {@code load} answers
 * {@code UNREADABLE} or {@code FUTURE_VERSION} for it. A scan that stopped noticing a torn write,
 * or started naming a corrupt one, breaks the case that produced it and names the shape.
 *
 * <p>{@code ModuleSidecarUnreadableScanCostTest} is the other half, holding the scan to a cost
 * that does not grow with the bodies it reads past. Both are needed: either one alone is trivially
 * satisfied by reverting the other.
 */
class ModuleSidecarUnreadableScanAgreementTest {

    /** A healthy version-3 sidecar as the writer produces it. */
    private static Path healthy(Path root, String moduleId) throws IOException {
        Files.createDirectories(root.resolve(moduleId));
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, moduleId);
        sidecar.putBody("claude", "a rendered guardrail body\nover two lines");
        sidecar.save(root);
        return root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId);
    }

    private static Path write(Path root, String moduleId, String content) throws IOException {
        Path path = root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    /**
     * Asserts the relation this fix rests on, and returns whether the file was named so that a
     * case can also state which side of it that file falls on.
     */
    private static boolean agrees(Path root, Path sidecar) {
        ModuleSidecar full = ModuleSidecar.load(sidecar);
        boolean fullSaysUnreadable =
            full == ModuleSidecar.UNREADABLE || full == ModuleSidecar.FUTURE_VERSION;
        boolean named = ModuleSidecar.unreadableSidecarNames(root)
            .contains(GuardrailFileWriter.fileName(sidecar));
        assertEquals(fullSaysUnreadable, named,
            "the scan and the full load disagree about " + sidecar.getFileName()
                + ": load said " + describe(full)
                + ", the scan " + (named ? "named" : "skipped") + " it");
        return named;
    }

    private static String describe(ModuleSidecar loaded) {
        if (loaded == null) return "null (corrupt or stale)";
        if (loaded == ModuleSidecar.UNREADABLE) return "UNREADABLE";
        if (loaded == ModuleSidecar.FUTURE_VERSION) return "FUTURE_VERSION";
        return "a sidecar";
    }

    @Test
    @DisplayName("a healthy sidecar is not named, by either route")
    void healthySidecarIsSilent(@TempDir Path root) throws IOException {
        assertFalse(agrees(root, healthy(root, "core")));
    }

    @Test
    @DisplayName("a newer format version is named, and is FUTURE_VERSION to the full load")
    void futureVersionIsNamed(@TempDir Path root) throws IOException {
        Path future = write(root, "future", "# version=99999\nmoduleId=future\n# end\n");

        assertSame(ModuleSidecar.FUTURE_VERSION, ModuleSidecar.load(future),
            "the fixture must actually reach the future-version branch, not fail earlier");
        assertTrue(agrees(root, future));
    }

    @Test
    @DisplayName("a torn write loses its trailer and is named")
    void tornWriteIsNamed(@TempDir Path root) throws IOException {
        Path sidecar = healthy(root, "torn");
        List<String> lines = Files.readAllLines(sidecar, StandardCharsets.UTF_8);
        assertEquals(ModuleSidecar.TRAILER, lines.get(lines.size() - 1), "fixture assumption");
        Files.write(sidecar, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(sidecar));
        assertTrue(agrees(root, sidecar));
    }

    @Test
    @DisplayName("a file that will not open at all is named")
    void unopenableIsNamed(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectory(root.resolve(ModuleSidecar.SIDECAR_PREFIX + "locked"));

        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(directory));
        assertTrue(agrees(root, directory));
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are named even with the trailer intact")
    void invalidUtf8IsNamed(@TempDir Path root) throws IOException {
        Path sidecar = root.resolve(ModuleSidecar.SIDECAR_PREFIX + "mojibake");
        byte[] head = "# version=3\nmoduleId=mojibake\nclaude=".getBytes(StandardCharsets.UTF_8);
        byte[] tail = "\n# end\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + 2 + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        all[head.length] = (byte) 0xC3;     // a UTF-8 lead byte...
        all[head.length + 1] = (byte) 0x28; // ...followed by something that cannot continue it
        System.arraycopy(tail, 0, all, head.length + 2, tail.length);
        Files.write(sidecar, all);

        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(sidecar),
            "readAllLines reports malformed input as an IOException, which is UNREADABLE");
        assertTrue(agrees(root, sidecar),
            "reading only the two ends of the file would miss this, and the module would vanish"
                + " with nothing said, which is the shape issue #592 was about");
    }

    @Test
    @DisplayName("a corrupt body is not named: the full load calls it null, and null is not a report")
    void corruptBodyIsNotNamed(@TempDir Path root) throws IOException {
        Path sidecar = healthy(root, "corrupt");
        List<String> rewritten = new ArrayList<>();
        for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
            rewritten.add(line.startsWith("claude=") ? "claude=not-base-64-at-all!" : line);
        }
        Files.write(sidecar, rewritten, StandardCharsets.UTF_8);

        assertNull(ModuleSidecar.load(sidecar), "fixture assumption: the body must be undecodable");
        assertFalse(agrees(root, sidecar));
    }

    @Test
    @DisplayName("a format older than the processor reads is not named")
    void staleFormatIsNotNamed(@TempDir Path root) throws IOException {
        Path stale = write(root, "stale", "# version=1\nmoduleId=stale\n# end\n");

        assertNull(ModuleSidecar.load(stale));
        assertFalse(agrees(root, stale));
    }

    @Test
    @DisplayName("an unparseable version header is not named")
    void unparseableVersionIsNotNamed(@TempDir Path root) throws IOException {
        Path odd = write(root, "odd", "# version=three\nmoduleId=odd\n# end\n");

        assertNull(ModuleSidecar.load(odd));
        assertFalse(agrees(root, odd));
    }

    @Test
    @DisplayName("no version header at all is not named, trailer or no trailer")
    void headerlessIsNotNamed(@TempDir Path root) throws IOException {
        assertFalse(agrees(root, write(root, "bare", "moduleId=bare\n# end\n")));
        assertFalse(agrees(root, write(root, "baretorn", "moduleId=baretorn\n")));
    }

    @Test
    @DisplayName("the pre-trailer format is read without one")
    void versionTwoNeedsNoTrailer(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("old"));
        Path old = write(root, "old", "# version=2\nmoduleId=old\nmodulePath=old\n");

        assertFalse(agrees(root, old),
            "version 2 never promised a trailer, so its absence is not evidence of a torn write");
    }

    @Test
    @DisplayName("the trailer is judged stripped, and past blank lines, exactly as isWhole judges it")
    void trailerIsMatchedTheWayIsWholeMatchesIt(@TempDir Path root) throws IOException {
        assertFalse(agrees(root, write(root, "indented", "# version=3\nmoduleId=indented\n   # end  \n")));
        assertFalse(agrees(root, write(root, "padded", "# version=3\nmoduleId=padded\n# end\n\n  \n")));
        assertFalse(agrees(root, write(root, "unterminated", "# version=3\nmoduleId=unterminated\n# end")));
        assertFalse(agrees(root, write(root, "crlf", "# version=3\r\nmoduleId=crlf\r\n# end\r\n")));
        assertTrue(agrees(root, write(root, "nearly", "# version=3\nmoduleId=nearly\n# ending\n")),
            "a line that merely starts like the trailer is not one");
        assertTrue(agrees(root, write(root, "after", "# version=3\nmoduleId=after\n# end\nclaude=eA==\n")),
            "the trailer must be last; content after it means the file grew past its own end");
    }

    @Test
    @DisplayName("every verdict is exercised: the fixture set is not silently all-silent")
    void bothVerdictsOccur(@TempDir Path root) throws IOException {
        healthy(root, "core");
        write(root, "future", "# version=99999\nmoduleId=future\n# end\n");
        Files.createDirectory(root.resolve(ModuleSidecar.SIDECAR_PREFIX + "locked"));

        List<String> named = ModuleSidecar.unreadableSidecarNames(root);

        assertEquals(
            List.of(ModuleSidecar.SIDECAR_PREFIX + "future", ModuleSidecar.SIDECAR_PREFIX + "locked"),
            named, "sorted, and holding both reportable verdicts but not the healthy sidecar");
    }
}
