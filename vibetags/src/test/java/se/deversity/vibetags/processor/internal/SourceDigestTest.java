package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The early exit's key (#834). A key that stays the same when an input changed is a false
 * "unchanged", which leaves guardrail files stale in silence, so each input it must cover is
 * changed here on its own and the key must move. Covering more than needed only costs an exit.
 */
class SourceDigestTest {

    @TempDir
    Path root;

    private Path source;

    private String key() {
        return SourceDigest.of("1.0", Map.of("vibetags.project", "p"), "core", false, root, root, List.of(source));
    }

    private void project() throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), "", StandardCharsets.UTF_8);
        source = root.resolve("A.java");
        Files.writeString(source, "class A {}", StandardCharsets.UTF_8);
    }

    @Test
    void theSameInputsGiveTheSameKey() throws IOException {
        project();
        assertNotNull(key());
        assertEquals(key(), key());
    }

    @Test
    void aSourceEditMovesTheKey() throws IOException {
        project();
        String before = key();
        Files.writeString(source, "class B {}", StandardCharsets.UTF_8);
        assertNotEquals(before, key(), "same length, different content");
    }

    @Test
    void aNewOptInMovesTheKey() throws IOException {
        project();
        String before = key();
        Files.createFile(root.resolve(".cursorrules"));
        assertNotEquals(before, key());
    }

    @Test
    void anOptInThatChangesKindMovesTheKey() throws IOException {
        // .clinerules is the cline file and the cline_granular directory: same path, other platform.
        project();
        Files.createFile(root.resolve(".clinerules"));
        String asFile = key();
        Files.delete(root.resolve(".clinerules"));
        Files.createDirectory(root.resolve(".clinerules"));
        assertNotEquals(asFile, key());
    }

    @Test
    void aRolesConfigEditMovesTheKey() throws IOException {
        project();
        Files.writeString(root.resolve(".vibetags-roles"), "core = com.example.*", StandardCharsets.UTF_8);
        String before = key();
        Files.writeString(root.resolve(".vibetags-roles"), "core = com.other.*", StandardCharsets.UTF_8);
        assertNotEquals(before, key());
    }

    @Test
    void theSidecarsCacheAndLogDoNotMoveTheKey() throws IOException {
        // Outputs and run records: covered by their own checks, and they change on every build.
        // The locks report exists before the baseline: its presence is an opt-in, which does move
        // the key, and only its content is output.
        project();
        Files.writeString(root.resolve(".vibetags-locks"), "a", StandardCharsets.UTF_8);
        String before = key();
        Files.writeString(root.resolve(".vibetags-cache"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".vibetags-mod-core"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".vibetags-locks"), "b", StandardCharsets.UTF_8);
        assertEquals(before, key());
    }

    @Test
    void everyOtherInputMovesTheKey() throws IOException {
        project();
        String base = key();
        List<Path> files = List.of(source);
        assertNotEquals(base, SourceDigest.of("1.1", Map.of("vibetags.project", "p"), "core", false, root, root, files));
        assertNotEquals(base, SourceDigest.of("1.0", Map.of("vibetags.project", "q"), "core", false, root, root, files));
        assertNotEquals(base, SourceDigest.of("1.0", Map.of("vibetags.project", "p"), "app", false, root, root, files));
        assertNotEquals(base, SourceDigest.of("1.0", Map.of("vibetags.project", "p"), "core", true, root, root, files));
    }

    @Test
    void theOrderSourcesArriveInDoesNotMatter() throws IOException {
        project();
        Path other = root.resolve("B.java");
        Files.writeString(other, "class B {}", StandardCharsets.UTF_8);
        assertEquals(
            SourceDigest.of("1.0", Map.of(), "core", false, root, root, List.of(source, other)),
            SourceDigest.of("1.0", Map.of(), "core", false, root, root, List.of(other, source)));
    }

    @Test
    void anUnreadableSourceGivesNoKey() throws IOException {
        project();
        Path missing = root.resolve("Gone.java");
        assertNull(SourceDigest.of("1.0", Map.of(), "core", false, root, root, List.of(source, missing)),
            "a source that cannot be hashed cannot be vouched for");
    }
}
