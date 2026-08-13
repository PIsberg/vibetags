package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Candidate-key derivation and the directory fallback.
 *
 * <p>The classpath half is exercised end to end by
 * {@code TransitiveGuardrailLifecycleE2ETest}, because it is only meaningful inside a real javac.
 */
class TransitiveManifestReaderTest {

    @Test
    void expandsAnImportIntoEveryPackagePrefix() {
        // Prefixes, because a library may govern a whole subtree from one package-info.java: an
        // import of a deep type must still find a manifest published for its parent package.
        Set<String> candidates = TransitiveManifestReader.candidatesFromImports(
            List.of("com.acme.crypto.api.CipherGateway"));
        assertEquals(Set.of("com.acme", "com.acme.crypto", "com.acme.crypto.api",
            "com.acme.crypto.api.CipherGateway"), candidates);
    }

    @Test
    void neverProbesASingleSegment() {
        // A one-segment key would collide across unrelated vendors and is not a package anyone
        // publishes a governing manifest for.
        Set<String> candidates = TransitiveManifestReader.candidatesFromImports(List.of("acme.Thing"));
        assertFalse(candidates.contains("acme"), "single-segment keys must not be probed: " + candidates);
        assertTrue(candidates.contains("acme.Thing"));
    }

    @Test
    void stripsWildcardImports() {
        assertEquals(Set.of("com.acme", "com.acme.crypto"),
            TransitiveManifestReader.candidatesFromImports(List.of("com.acme.crypto.*")));
    }

    @Test
    void skipsThePlatformAndLanguageRuntimes() {
        // These ship no manifests, and a project with a hundred java.util imports would otherwise
        // pay for a hundred guaranteed misses on every compile.
        Set<String> candidates = TransitiveManifestReader.candidatesFromImports(List.of(
            "java.util.List", "javax.tools.Diagnostic", "jdk.internal.X", "sun.misc.Unsafe",
            "com.sun.source.tree.Tree", "org.w3c.dom.Node", "org.xml.sax.XMLReader",
            "kotlin.collections.CollectionsKt", "scala.collection.Seq", "groovy.lang.Closure",
            "clojure.lang.RT"));
        assertEquals(Set.of(), candidates, "unexpected candidates: " + candidates);
    }

    @Test
    void deduplicatesAcrossImportsAndReturnsAStableOrder() {
        Set<String> candidates = TransitiveManifestReader.candidatesFromImports(List.of(
            "com.acme.crypto.api.A", "com.acme.crypto.api.B", "com.acme.crypto.spi.C"));
        assertEquals(List.of("com.acme", "com.acme.crypto", "com.acme.crypto.api",
                "com.acme.crypto.api.A", "com.acme.crypto.api.B",
                "com.acme.crypto.spi", "com.acme.crypto.spi.C"),
            List.copyOf(candidates),
            "order must be a function of the imports, not of the order javac walked them");
    }

    @Test
    void toleratesMalformedImportText() {
        Set<String> candidates = TransitiveManifestReader.candidatesFromImports(
            List.of("", "   ", ".", "..", "a.", "com..acme"));
        assertFalse(candidates.contains(""), "empty keys must never be probed");
        for (String candidate : candidates) {
            assertFalse(candidate.endsWith("."), "a trailing dot is not a package: " + candidate);
        }
    }

    @Test
    void readsEveryManifestInADirectory(@TempDir Path dir) throws IOException {
        writeManifest(dir, "com.acme.api", "@AISecure");
        writeManifest(dir, "com.other.api", "@AIPerformance");
        Files.writeString(dir.resolve("not-a-manifest.txt"), "ignored");

        TransitiveManifestReader reader = new TransitiveManifestReader(null);
        assertEquals(List.of(), reader.resolveDirectory(dir), "nothing should have been rejected");

        Set<String> packages = reader.rules().stream()
            .map(TransitiveRule::packageName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("com.acme.api", "com.other.api"), packages);
    }

    @Test
    void reportsUnreadableManifestsInsteadOfDroppingThemSilently(@TempDir Path dir) throws IOException {
        writeManifest(dir, "com.acme.api", "@AISecure");
        Files.writeString(dir.resolve("com.broken.api.json"), "{ this is not json");

        TransitiveManifestReader reader = new TransitiveManifestReader(null);
        List<String> rejected = reader.resolveDirectory(dir);

        assertEquals(1, rejected.size(), "the broken file should be named, not swallowed: " + rejected);
        assertTrue(rejected.get(0).startsWith("com.broken.api.json"), rejected.get(0));
        assertEquals(1, reader.rules().size(), "the readable manifest must still be read");
    }

    @Test
    void aMissingDirectoryIsNotAnError(@TempDir Path dir) {
        TransitiveManifestReader reader = new TransitiveManifestReader(null);
        assertEquals(List.of(), reader.resolveDirectory(dir.resolve("nope")));
        assertTrue(reader.rules().isEmpty());
    }

    @Test
    void optInIsAFilePresenceCheck(@TempDir Path root) throws IOException {
        assertFalse(TransitiveManifestReader.optedIn(root));
        Files.createFile(root.resolve(TransitiveManifestReader.MARKER_FILE));
        assertTrue(TransitiveManifestReader.optedIn(root));
    }

    private static void writeManifest(Path dir, String pkg, String label) throws IOException {
        Files.writeString(dir.resolve(pkg + ".json"), TransitiveManifest.toJson(pkg, "com.acme:x:1",
            List.of(new TransitiveRule("com.acme:x:1", pkg, label,
                TransitiveManifest.tierOf(label), java.util.Map.of("note", "n"))), "1.0"));
    }
}
