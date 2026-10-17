package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the real source file out of what kapt and Gradle leave beside a stub (#955).
 *
 * <p>{@link #kaptMetadata} writes the layout kapt 2.4.10 was measured writing: an
 * {@link ObjectOutputStream} holding only block data, version 1, an entry count, and per entry the
 * internal name (with {@code #member} for a member), the source path, a relative flag and an
 * offset. The end-to-end check against kapt itself is {@code examples/kotlin}, whose rule files
 * CI compares with the KSP front end's.
 */
@DisplayName("StubSourceNames reads the source file behind a kapt or Gradle Groovy stub")
class StubSourceNamesTest {

    private static byte[] kaptMetadata(int version, String... keyThenPath) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeInt(version);
            out.writeInt(keyThenPath.length / 2);
            for (int i = 0; i < keyThenPath.length; i += 2) {
                out.writeUTF(keyThenPath[i]);
                out.writeUTF(keyThenPath[i + 1]);
                out.writeBoolean(false);
                out.writeInt(17);
            }
            // kapt follows the entries with a second table; the reader must not need it
            out.writeInt(1);
            out.writeUTF("<init>(I)V");
            out.writeUTF("<init>(int)");
        }
        return bytes.toByteArray();
    }

    private static Path stub(Path dir, String name, byte[] metadata) throws IOException {
        Path stub = dir.resolve(name + ".java");
        Files.writeString(stub, "class " + name + " {}");
        if (metadata != null) {
            Files.write(dir.resolve(name + ".kapt_metadata"), metadata);
        }
        return stub;
    }

    @Test
    @DisplayName("the entry for the type itself names its .kt file, members of it or not")
    void theTypesOwnEntryNamesTheFile(@TempDir Path dir) throws IOException {
        Path stub = stub(dir, "Invoice", kaptMetadata(1,
            "com/example/Invoice#total", "/src/main/kotlin/com/example/Other.kt",
            "com/example/Invoice", "/src/main/kotlin/com/example/Billing.kt"));

        assertEquals("Billing.kt", StubSourceNames.kotlinFileName(stub, "com/example/Invoice"));
    }

    @Test
    @DisplayName("a Windows path and a lowercase file name come through as written")
    void windowsPathAndLowercaseName(@TempDir Path dir) throws IOException {
        Path stub = stub(dir, "LedgerutilsKt", kaptMetadata(1,
            "com/example/LedgerutilsKt", "C:\\work\\src\\main\\kotlin\\com\\example\\ledgerutils.kt"));

        assertEquals("ledgerutils.kt", StubSourceNames.kotlinFileName(stub, "com/example/LedgerutilsKt"));
    }

    @Test
    @DisplayName("an entry spread over several serialization blocks is read whole")
    void entriesAcrossBlocks(@TempDir Path dir) throws IOException {
        String[] entries = new String[400];
        for (int i = 0; i < entries.length; i += 2) {
            entries[i] = "com/example/Big#member" + i + "(Ljava/lang/String;)V";
            entries[i + 1] = "/a/rather/long/source/path/that/fills/blocks/quickly/com/example/Big.kt";
        }
        entries[entries.length - 2] = "com/example/Big";
        entries[entries.length - 1] = "/src/com/example/Huge.kt";
        byte[] metadata = kaptMetadata(1, entries);
        assertTrue(metadata.length > 1024 * 3, "precondition: the stream holds several blocks");

        assertEquals("Huge.kt", StubSourceNames.kotlinFileName(stub(dir, "Big", metadata), "com/example/Big"));
    }

    @Test
    @DisplayName("no metadata, no entry for the type, or a version it does not know: no answer")
    void nothingToGoOn(@TempDir Path dir) throws IOException {
        assertNull(StubSourceNames.kotlinFileName(stub(dir, "Bare", null), "com/example/Bare"));
        Path other = stub(dir, "Other", kaptMetadata(1, "com/example/Other#x", "/src/Other.kt"));
        assertNull(StubSourceNames.kotlinFileName(other, "com/example/Other"),
            "a member's entry is not the type's own");
        assertNull(StubSourceNames.kotlinFileName(stub(dir, "Next", kaptMetadata(2, "com/example/Next", "/src/Next.kt")),
            "com/example/Next"));
        assertNull(StubSourceNames.kotlinFileName(stub(dir, "Odd", kaptMetadata(1, "com/example/Odd", "/src/Odd.java")),
            "com/example/Odd"), "only a .kt file is a kapt source");
    }

    @Test
    @DisplayName("a stream holding an object, or truncated, or not a stream at all is not read")
    void notBlockDataOnly(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeInt(1);
            out.writeObject("com/example/Obj");
        }
        assertNull(StubSourceNames.blockData(bytes.toByteArray()), "an object in the stream ends the read");
        assertNull(StubSourceNames.kotlinFileName(stub(dir, "Obj", bytes.toByteArray()), "com/example/Obj"));

        byte[] whole = kaptMetadata(1, "com/example/Cut", "/src/Cut.kt");
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length - 30);
        assertNull(StubSourceNames.kotlinFileName(stub(dir, "Cut", cut), "com/example/Cut"));
        assertNull(StubSourceNames.kotlinFileName(stub(dir, "Text", "not a stream".getBytes(StandardCharsets.UTF_8)), "com/example/Text"));
    }

    @Test
    @DisplayName("a stub is Groovy's when it sits under Gradle's groovy-java-stubs directory")
    void gradleGroovyStubDirectory() {
        assertTrue(StubSourceNames.isGradleGroovyStub(
            Path.of("build", "tmp", "compileGroovy", "groovy-java-stubs", "com", "example", "Tracked.java")));
        assertFalse(StubSourceNames.isGradleGroovyStub(Path.of("src", "main", "java", "com", "example", "Tracked.java")));
        assertFalse(StubSourceNames.isGradleGroovyStub(Path.of("src", "groovy-java-stubs-old", "Tracked.java")));
    }
}
