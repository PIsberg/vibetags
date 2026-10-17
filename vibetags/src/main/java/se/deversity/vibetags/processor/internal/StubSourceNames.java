package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The real source file behind a generated Java stub, for the stubs that leave a trace of it (#955).
 *
 * <p>Under kapt and Groovy's stub generation javac reads a {@code .java} stub, so the name a round
 * reports is the stub's. {@link ElementNaming#sourceFileName} then guesses {@code <Type>.kt} or
 * {@code <Type>.groovy}, which misses a class in a differently named file, a file facade renamed by
 * {@code @file:JvmName}, a lowercase file name, and a Groovy interface. Measured on Kotlin 2.4.10's
 * kapt and Groovy 5.1.0 under Gradle 9.7.1 (2026-10-17):
 *
 * <ul>
 *   <li><strong>kapt</strong> writes {@code <Stub>.kapt_metadata} beside every stub. It is a Java
 *       serialization stream holding only block data: an {@code int} version (1), an {@code int}
 *       count, then per entry the UTF strings {@code internal name[#member]} and the source path, a
 *       {@code boolean} saying whether the path is relative, and an {@code int} offset. Every stub
 *       measured, including an interface with no members, has an entry for the type itself, whose
 *       path names the {@code .kt} file. This is kapt's line-mapping data, not a documented
 *       contract, so anything unexpected in it means the guess stands.</li>
 *   <li><strong>Groovy</strong> stubs name nothing, but Gradle writes them under a directory
 *       named {@code groovy-java-stubs}, and an interface's stub carries no other marker. Under it a
 *       stub is taken to be Groovy's. The file name still has to be guessed from the type, so a
 *       Groovy type in a differently named file keeps missing.</li>
 * </ul>
 *
 * <p>Reads at most {@link #MAX_BYTES} of one file per Kotlin top-level type and never throws: any
 * failure returns {@code null} and leaves the caller's guess in place.
 */
final class StubSourceNames {

    /** The directory Gradle's {@code GroovyCompile} writes joint-compilation stubs to. */
    static final String GRADLE_GROOVY_STUB_DIR = "groovy-java-stubs";

    /** A larger file is not read; kapt writes a few hundred bytes per class. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final int STREAM_MAGIC = 0xACED;
    private static final int STREAM_VERSION = 5;
    private static final int TC_BLOCKDATA = 0x77;
    private static final int TC_BLOCKDATALONG = 0x7A;
    private static final int KAPT_METADATA_VERSION = 1;

    private StubSourceNames() {}

    /**
     * The name of the {@code .kt} file kapt generated {@code stub} from, read from the
     * {@code .kapt_metadata} beside it, or {@code null} when there is none or it does not name a
     * file for {@code internalName} ({@code com/example/Invoice}).
     */
    static @Nullable String kotlinFileName(Path stub, String internalName) {
        Path name = stub.getFileName();
        if (name == null || !name.toString().endsWith(".java")) {
            return null;
        }
        String stem = name.toString().substring(0, name.toString().length() - ".java".length());
        Path metadata = stub.resolveSibling(stem + ".kapt_metadata");
        try {
            if (!Files.isRegularFile(metadata) || Files.size(metadata) > MAX_BYTES) {
                return null;
            }
            byte[] data = blockData(Files.readAllBytes(metadata));
            return data.length == 0 ? null : sourceFor(data, internalName);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Whether {@code stub} sits under the directory Gradle writes Groovy's stubs to. */
    static boolean isGradleGroovyStub(Path stub) {
        for (Path part : stub) {
            if (GRADLE_GROOVY_STUB_DIR.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block data of a serialization stream, concatenated, or an empty array when the stream
     * holds anything else, which no kapt file does. Nothing is deserialized: an object, a class or
     * any other tag ends the read.
     */
    static byte[] blockData(byte[] stream) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(stream));
        if (in.readUnsignedShort() != STREAM_MAGIC || in.readUnsignedShort() != STREAM_VERSION) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(stream.length);
        int tag;
        while ((tag = in.read()) != -1) {
            int length;
            if (tag == TC_BLOCKDATA) {
                length = in.readUnsignedByte();
            } else if (tag == TC_BLOCKDATALONG) {
                length = in.readInt();
            } else {
                return new byte[0];
            }
            if (length < 0 || length > in.available()) {
                return new byte[0];
            }
            byte[] block = new byte[length];
            in.readFully(block);
            out.write(block);
        }
        return out.toByteArray();
    }

    /** The file name of the entry for {@code internalName} itself, or {@code null}. */
    private static @Nullable String sourceFor(byte[] data, String internalName) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != KAPT_METADATA_VERSION) {
            return null;
        }
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            String path = in.readUTF();
            in.readBoolean();
            in.readInt();
            if (key.equals(internalName)) {
                String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
                return file.endsWith(".kt") ? file : null;
            }
        }
        return null;
    }
}
