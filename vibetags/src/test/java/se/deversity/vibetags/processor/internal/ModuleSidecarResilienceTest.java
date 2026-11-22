package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ModuleSidecar} does when the filesystem, not the content, is the problem.
 *
 * <p>The read side was a bug found by {@code ModuleSidecarAsyncTest} on Windows, where a parallel
 * reactor has one module renaming its sidecar into place while a sibling reads the same directory:
 * the read was folded into "malformed" and deleted a valid sibling's sidecar. It is not
 * reproducible on Linux, which is why these are deterministic unit tests rather than more stress.
 * The write side, the rename that the reader's open handle aborts, is {@link AtomicFileReplacerTest}.
 */
class ModuleSidecarResilienceTest {

    // ---------------------------------------------------------------- unreadable vs malformed

    @Test
    void anUnreadableFileIsNotTheSameAsAMalformedOne(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("never-existed");
        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(missing),
            "a file that cannot be read says nothing about its content");

        // With the trailer: the write finished, so a value that will not decode is corrupt. Without
        // it the same file is a write cut short, which is UNREADABLE (see the next test).
        Path corrupt = Files.writeString(dir.resolve(".vibetags-mod-corrupt"),
            "# version=" + ModuleSidecar.FORMAT_VERSION + "\nmoduleId=corrupt\nclaude=not~valid~base64!\n"
                + ModuleSidecar.TRAILER + "\n");
        assertNull(ModuleSidecar.load(corrupt), "content that will not decode is malformed");
    }

    @Test
    void readAllPrunesAMalformedSidecarButNeverAnUnreadableOne(@TempDir Path root) throws IOException {
        ModuleSidecar valid = new ModuleSidecar("good", "good");
        valid.putBody("claude", "body");
        Files.createDirectories(root.resolve("good"));
        valid.save(root);

        // Whole (it ends with the trailer) but not a sidecar: that combination is what the
        // caller may delete. A file with no trailer is treated as cut short instead.
        Path malformed = Files.writeString(root.resolve(".vibetags-mod-bad"),
            "not a sidecar at all\n# end\n");

        // A directory sitting where a sidecar file should be is the portable stand-in for
        // "cannot be read": every OS refuses to read it, none of them call it corrupt.
        Path unreadable = Files.createDirectory(root.resolve(".vibetags-mod-locked"));

        List<ModuleSidecar> all = ModuleSidecar.readAll(root);

        assertEquals(1, all.size(), "only the valid sidecar should be merged");
        assertEquals("good", all.get(0).getModuleId());
        assertFalse(Files.exists(malformed), "a malformed sidecar should still be pruned");
        assertTrue(Files.exists(unreadable),
            "readAll deleted a sidecar it could not even read — that is how a parallel reactor "
                + "loses a module's guardrails");
    }

    @Test
    void loadForReportsNothingRatherThanASentinelWhenTheFileCannotBeRead(@TempDir Path root) throws IOException {
        Path asDirectory = root.resolve(".vibetags-mod-locked");
        Files.createDirectory(asDirectory);

        assertNull(ModuleSidecar.loadFor(root, "locked"),
            "a caller comparing against last round's content must not be handed a sentinel");

        ModuleSidecar saved = new ModuleSidecar("real", "real");
        saved.putBody("claude", "body");
        Files.createDirectories(root.resolve("real"));
        saved.save(root);
        ModuleSidecar reloaded = ModuleSidecar.loadFor(root, "real");
        assertNotNull(reloaded);
        assertEquals("body", reloaded.getBodies().get("claude"));
    }

    // ---------------------------------------------------------------- truncated sidecar

    /**
     * A sidecar cut short — a torn write, a full disk, a copy interrupted mid-flight — used to
     * load as if nothing were wrong. Base64 accepts unpadded input, so the body decoded to
     * whatever survived the cut and that half-sentence was rendered into every sibling module's
     * aggregate. Seven bytes is deliberately small: the file still parses, the header is intact,
     * and only a trailer written last can tell the difference (issue #553).
     */
    @Test
    void aSidecarCutShortIsUnreadableRatherThanQuietlyHalfDecoded(@TempDir Path root)
            throws IOException {
        Files.createDirectories(root.resolve("core"));
        ModuleSidecar saved = new ModuleSidecar("core", "core");
        saved.putBody("claude", "## Guardrails\n\n- a line long enough to survive a small cut\n");
        saved.save(root);

        Path file = root.resolve(".vibetags-mod-core");
        byte[] full = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(full, full.length - 7));

        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(file),
            "a truncated sidecar decoded to a body that was never saved, and that body was "
                + "rendered into every sibling module's aggregate");
        assertTrue(ModuleSidecar.readAll(root).isEmpty(), "a reader must not use what it cannot trust");
        assertTrue(Files.exists(file),
            "and must not delete it either: unreadable is not evidence the content is garbage");
    }

    /**
     * The trailer has to be invisible to a processor that predates it, or a mixed-version reactor
     * loses every module compiled by the newer half. Older readers skip any {@code #} line they do
     * not recognise, which is what makes appending one safe; this pins that rule from the other
     * side, with a comment key this version has never written.
     */
    @Test
    void anUnknownCommentLineIsSkippedRatherThanParsed(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve(".vibetags-mod-core"),
            "# version=2\n# crc=deadbeef\nmoduleId=core\nmodulePath=core\nregionId=core\n"
                + "claude=" + java.util.Base64.getEncoder().encodeToString(
                    "body".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "\n# end\n");

        ModuleSidecar loaded = ModuleSidecar.load(root.resolve(".vibetags-mod-core"));
        assertNotNull(loaded);
        assertEquals("body", loaded.getBodies().get("claude"),
            "an unrecognised comment key must not become a body, and must not stop the parse");
    }

    /**
     * The source roots a round compiled from survive the file, with forward slashes whichever
     * system wrote them, and are never read back as a service body: the partial-round guard walks
     * them to reach a root outside the module directory (#1065), and a body would be rendered into
     * the aggregate.
     */
    @Test
    void theSourceRootsRoundTripThroughTheFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        ModuleSidecar saved = new ModuleSidecar("app", "app");
        saved.putBody("claude", "body");
        saved.setSourceRoots(List.of("app/src/main/java", "..\\shared", "", "shared/src"));
        saved.save(root);

        ModuleSidecar loaded = ModuleSidecar.load(root.resolve(".vibetags-mod-app"));
        assertNotNull(loaded);
        assertEquals(List.of("app/src/main/java", "../shared", "shared/src"), List.copyOf(loaded.getSourceRoots()));
        assertEquals(java.util.Set.of("claude"), loaded.getBodies().keySet(),
            "the recorded roots must not come back as a body");
    }

    /** A sidecar written before #1065 records no roots and still loads, with none. */
    @Test
    void aSidecarWithoutSourceRootsLoadsWithNone(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve(".vibetags-mod-core"),
            "# version=3\nmoduleId=core\nmodulePath=core\nregionId=core\n"
                + "claude=" + java.util.Base64.getEncoder().encodeToString(
                    "body".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "\n# end\n");

        ModuleSidecar loaded = ModuleSidecar.load(root.resolve(".vibetags-mod-core"));
        assertNotNull(loaded);
        assertTrue(loaded.getSourceRoots().isEmpty());
        assertEquals("body", loaded.getBodies().get("claude"));
    }

    // ---------------------------------------------------------------- the pre-trailer format

    /**
     * A sidecar written before the {@code # end} trailer existed must still be readable.
     *
     * <p>The trailer was appended by #572 without a format-version bump, deliberately, so that an
     * older processor would keep reading a newer file. The other direction was documented as
     * costing "skipped as unreadable until its module recompiles", and that cost turned out to be
     * a silent module loss: {@code readAll} returns fewer sidecars than are on disk, the round
     * therefore merges as single-module, and every sibling region disappears from the aggregate
     * and from the granular role files. Measured on a real consumer (issue #590): the first build
     * after upgrading dropped 30 lines from CLAUDE.md, 35 from GEMINI.md and emptied a role file,
     * and because such sidecars are skipped rather than pruned, nothing repaired them until each
     * module happened to recompile.
     *
     * <p>A file this reader could not open and a file written by a processor that never wrote
     * trailers are not the same thing, and only the first is a reason to drop a module.
     */
    @Test
    void aSidecarWrittenBeforeTheTrailerExistedIsStillRead(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        // Byte-for-byte the shape written before the trailer existed: header, keys, body.
        Files.writeString(root.resolve(".vibetags-mod-core"), """
            # version=2
            moduleId=core
            modulePath=core
            regionId=core
            claude=%s
            """.formatted(encoded("legacy body")));

        ModuleSidecar loaded = ModuleSidecar.load(root.resolve(".vibetags-mod-core"));

        assertNotNull(loaded, "a complete pre-trailer sidecar is not malformed");
        assertNotSame(ModuleSidecar.UNREADABLE, loaded,
            "a sidecar with no trailer was written by a processor that wrote none, not torn in half");
        assertEquals("legacy body", loaded.getBodies().get("claude"));
    }

    /**
     * The consequence the consumer actually saw: one module already recompiled under the new
     * processor, its siblings still carrying pre-trailer sidecars, and the merge reduced to the one
     * region it could read. Asserted through {@code readAll} because that is what decides
     * {@code isMultiModule}, and a count of one is what turns a reactor aggregate into a
     * single-module document.
     */
    @Test
    void aReactorMixingPreTrailerAndCurrentSidecarsKeepsEveryRegion(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("app"));
        // core has not recompiled yet, so it is still in the pre-trailer shape.
        Files.writeString(root.resolve(".vibetags-mod-core"), """
            # version=2
            moduleId=core
            modulePath=core
            regionId=core
            claude=%s
            """.formatted(encoded("core body")));
        // app recompiled under this processor, so it carries the trailer.
        Files.writeString(root.resolve(".vibetags-mod-app"), """
            # version=2
            moduleId=app
            modulePath=app
            regionId=app
            claude=%s
            # end
            """.formatted(encoded("app body")));

        List<ModuleSidecar> all = ModuleSidecar.readAll(root);

        assertEquals(List.of("app", "core"),
            all.stream().map(ModuleSidecar::getModuleId).sorted().toList(),
            "both modules must reach the merge; dropping one writes an aggregate missing its region");
        assertTrue(Files.exists(root.resolve(".vibetags-mod-core")),
            "and a pre-trailer sidecar is never pruned - deleting it would lose the module for good");
    }

    private static String encoded(String body) {
        return java.util.Base64.getEncoder()
            .encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- naming what could not be read

    /**
     * A round that cannot read a sidecar is about to write an aggregate without that module, and
     * until now said so only at DEBUG - which nobody has on, so the module simply vanished from
     * CLAUDE.md and the developer met it as an unexplained diff. Issue #590 reached that state
     * through an older format; issue #592 reaches it through a newer one, which this processor
     * cannot prevent because the round that drops the regions belongs to the older processor.
     * Naming the files is what turns a silent loss into a stated one.
     *
     * <p>Both unreadable shapes count and a healthy sidecar does not, because a warning that fires
     * on every build is one people turn off.
     */
    @Test
    void everySidecarThisProcessorCannotReadIsNamed(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("newer"));
        Files.createDirectories(root.resolve("torn"));
        // Readable: this one must not be named, or the warning cries wolf on a healthy reactor.
        Files.writeString(root.resolve(".vibetags-mod-core"), """
            # version=2
            moduleId=core
            modulePath=core
            regionId=core
            claude=%s
            """.formatted(encoded("core body")));
        // Written by a newer processor: a mixed-version reactor.
        Files.writeString(root.resolve(".vibetags-mod-newer"), """
            # version=99
            moduleId=newer
            modulePath=newer
            regionId=newer
            # end
            """);
        // Current format, promising a trailer, cut short before it: a torn write.
        Files.writeString(root.resolve(".vibetags-mod-torn"), """
            # version=%d
            moduleId=torn
            modulePath=torn
            regionId=torn
            claude=%s
            """.formatted(ModuleSidecar.FORMAT_VERSION, encoded("half a body")));

        List<String> named = ModuleSidecar.unreadableSidecarNames(root);

        assertEquals(List.of(".vibetags-mod-newer", ".vibetags-mod-torn"), named,
            "both unreadable shapes are named, and the healthy sidecar is not");
        assertTrue(Files.exists(root.resolve(".vibetags-mod-newer")),
            "naming a sidecar must never be a step towards deleting it");
        assertTrue(Files.exists(root.resolve(".vibetags-mod-torn")), "nor this one");
    }

    /** A reactor whose sidecars are all readable says nothing, so the warning stays meaningful. */
    @Test
    void aHealthyReactorNamesNothing(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve(".vibetags-mod-core"), """
            # version=2
            moduleId=core
            modulePath=core
            regionId=core
            claude=%s
            """.formatted(encoded("core body")));

        assertTrue(ModuleSidecar.unreadableSidecarNames(root).isEmpty());
    }

    // ---------------------------------------------------------------- unrepresentable module path

    /**
     * A sibling sidecar can name a module directory this filesystem cannot even spell: written on
     * Linux for a module called {@code api:v2} or {@code core } and synced into a Windows checkout,
     * or hand-edited. {@code root.resolve} then throws InvalidPathException, a RuntimeException
     * that escaped readAll, staleGranularStems and anyStale alike and failed every module of the
     * reactor with an uncaught-exception diagnostic. A NUL byte is illegal everywhere, so the
     * case reproduces on Linux as well. The module cannot exist here, so it is stale, not fatal.
     */
    @Test
    void aModulePathThisFilesystemRejectsIsStaleNotFatal(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".vibetags-mod-core"),
            "# version=2\nmoduleId=core\nmodulePath=core" + (char) 0 + "x\nregionId=core\n# end\n");

        assertTrue(ModuleSidecar.anyStale(root), "the module cannot exist here, so its sidecar is stale");
        assertTrue(ModuleSidecar.staleGranularStems(root).isEmpty(),
            "it names no live stems and, above all, does not throw");
        assertTrue(ModuleSidecar.readAll(root).isEmpty(),
            "a module whose directory cannot exist here contributes nothing");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-core")),
            "and the reading round prunes it like any other stale sidecar");
    }
}
