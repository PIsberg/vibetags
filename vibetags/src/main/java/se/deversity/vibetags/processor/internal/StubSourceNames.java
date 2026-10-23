package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
 *       named {@code groovy-java-stubs}, and GMavenPlus under {@code generated-sources/groovy-stubs}
 *       (measured with gmavenplus-plugin 4.2.1 and Groovy 5.0.2, 2026-10-23), and an interface's
 *       stub carries no other marker. Under either a stub is taken to be Groovy's. Neither groovyc
 *       nor the build tool records which file a type came from, so the file is looked for where the
 *       build tool's layout puts the package's sources ({@link #groovyFileName}, #973).</li>
 * </ul>
 *
 * <p>Reads at most {@link #MAX_BYTES} of one file per Kotlin top-level type and never throws: any
 * failure returns {@code null} and leaves the caller's guess in place.
 */
final class StubSourceNames {

    /** The directory Gradle's {@code GroovyCompile} writes joint-compilation stubs to. */
    static final String GRADLE_GROOVY_STUB_DIR = "groovy-java-stubs";

    /** The directory GMavenPlus's {@code generateStubs} writes to, under {@code generated-sources}. */
    static final String GMAVENPLUS_STUB_DIR = "groovy-stubs";

    private static final String GENERATED_SOURCES = "generated-sources";

    /** A larger file is not read; kapt writes a few hundred bytes per class. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final int STREAM_MAGIC = 0xACED;
    private static final int STREAM_VERSION = 5;
    private static final int TC_BLOCKDATA = 0x77;
    private static final int TC_BLOCKDATALONG = 0x7A;
    private static final int KAPT_METADATA_VERSION = 1;

    private StubSourceNames() {}

    /**
     * The {@code .kapt_metadata} beside {@code stub} when the stub sits in a kapt stub directory
     * (Gradle's {@code kapt3}, kotlin-maven-plugin's {@code kaptStubs}) and the file exists, else
     * {@code null}. The directory test is a name match, so a Java source costs no file access.
     */
    static @Nullable Path kaptMetadataOf(Path stub) {
        boolean kapt = false;
        for (Path part : stub) {
            String name = part.toString();
            if ("kapt3".equals(name) || "kaptStubs".equals(name)) {
                kapt = true;
            }
        }
        Path file = stub.getFileName();
        if (!kapt || file == null || !file.toString().endsWith(".java")) {
            return null;
        }
        String stem = file.toString().substring(0, file.toString().length() - ".java".length());
        Path metadata = stub.resolveSibling(stem + ".kapt_metadata");
        return Files.isRegularFile(metadata) ? metadata : null;
    }

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

    /**
     * The {@code .groovy} file that declares the top-level type {@code simpleName}, whose stub is
     * {@code stub}, or {@code null} when that cannot be told (#973).
     *
     * <p>Gradle writes a stub to {@code <project>/<buildDir>/tmp/<task>/groovy-java-stubs/<package>/}
     * and GMavenPlus to {@code <project>/<buildDir>/generated-sources/groovy-stubs/<sourceSet>/<package>/},
     * and both read {@code <project>/src/<sourceSet>/groovy/} by convention, the source set being
     * Gradle's {@code compile<SourceSet>Groovy} task. In that package directory
     * {@code <simpleName>.groovy} wins when it exists; otherwise the one file with a line declaring
     * the type ({@code class}, {@code interface}, {@code trait}, {@code enum} or {@code record},
     * after any modifiers, outside a comment) names it. No directory, another layout, no declaring
     * file or two of them all return {@code null}, which leaves the caller's guess of
     * {@code <simpleName>.groovy} in place. Reads only the {@code .groovy} files of one directory,
     * each at most {@link #MAX_BYTES}, and never throws.
     */
    static @Nullable String groovyFileName(Path stub, String simpleName) {
        try {
            Path dir = groovySourceDir(stub);
            if (dir == null || !Files.isDirectory(dir)) {
                return null;
            }
            String own = simpleName + ".groovy";
            if (Files.isRegularFile(dir.resolve(own))) {
                return own;
            }
            Pattern declares = Pattern.compile("(?m)^[ \\t]*(?:@[\\w.]+(?:\\([^)\\n]*\\))?[ \\t]+)*"
                + "(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp)[ \\t]+)*"
                + "(?:class|interface|trait|enum|record)[ \\t]+" + Pattern.quote(simpleName) + "\\b");
            String found = null;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.groovy")) {
                for (Path file : files) {
                    if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES
                        || !declares.matcher(withoutComments(Files.readString(file, StandardCharsets.UTF_8))).find()) {
                        continue;
                    }
                    if (found != null) {
                        return null; // two candidates: not ours to pick
                    }
                    Path name = file.getFileName();
                    found = name == null ? null : name.toString();
                }
            }
            return found;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * {@code source} with its block and line comments blanked out, newlines kept, so a commented-out
     * declaration does not read as one. A linear scan rather than a regular expression, for the
     * reason {@code ElementNaming.stripAnnotations} gives. String literals are not tracked: a
     * {@code //} inside one blanks the rest of its line, which never holds a type declaration.
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : 0;
            if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int j = i; j < stop; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else if (c == '/' && next == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * {@code <project>/src/<sourceSet>/groovy/<package>} for a stub at
     * {@code <project>/<buildDir>/tmp/compile<SourceSet>Groovy/groovy-java-stubs/<package>/<Type>.java}
     * (Gradle) or {@code <project>/<buildDir>/generated-sources/groovy-stubs/<sourceSet>/<package>/<Type>.java}
     * (GMavenPlus), or {@code null} for any other layout.
     */
    private static @Nullable Path groovySourceDir(Path stub) {
        int names = stub.getNameCount();
        int gradle = lastIndexOf(stub, GRADLE_GROOVY_STUB_DIR);
        if (gradle >= 3 && "tmp".equals(stub.getName(gradle - 2).toString())) {
            String task = stub.getName(gradle - 1).toString();
            if (!task.startsWith("compile") || !task.endsWith("Groovy") || task.length() < "compileGroovy".length()) {
                return null;
            }
            String set = task.substring("compile".length(), task.length() - "Groovy".length());
            String sourceSet = set.isEmpty() ? "main" : Character.toLowerCase(set.charAt(0)) + set.substring(1);
            return sourceDir(stub, gradle - 3, sourceSet, gradle + 1);
        }
        int gmaven = lastIndexOf(stub, GMAVENPLUS_STUB_DIR);
        if (gmaven >= 2 && GENERATED_SOURCES.equals(stub.getName(gmaven - 1).toString()) && gmaven + 2 < names) {
            return sourceDir(stub, gmaven - 2, stub.getName(gmaven + 1).toString(), gmaven + 2);
        }
        return null;
    }

    /**
     * {@code <project>/src/<sourceSet>/groovy/<package>}, where the project is the first
     * {@code projectEnd} names of {@code stub} and the package runs from name {@code packageStart}
     * to the stub's parent.
     */
    private static @Nullable Path sourceDir(Path stub, int projectEnd, String sourceSet, int packageStart) {
        Path root = stub.getRoot();
        Path project = projectEnd == 0 ? null : stub.subpath(0, projectEnd);
        Path base = project == null ? root : root == null ? project : root.resolve(project);
        if (base == null) {
            return null;
        }
        Path dir = base.resolve("src").resolve(sourceSet).resolve("groovy");
        int names = stub.getNameCount();
        return packageStart < names - 1 ? dir.resolve(stub.subpath(packageStart, names - 1).toString()) : dir;
    }

    private static int lastIndexOf(Path path, String name) {
        int at = -1;
        for (int i = 0; i < path.getNameCount(); i++) {
            if (name.equals(path.getName(i).toString())) {
                at = i;
            }
        }
        return at;
    }

    /**
     * Whether {@code stub} sits under a directory Gradle or GMavenPlus writes Groovy's stubs to:
     * Gradle's {@code groovy-java-stubs}, or GMavenPlus's {@code generated-sources/groovy-stubs}.
     */
    static boolean isGroovyStub(Path stub) {
        int gmaven = lastIndexOf(stub, GMAVENPLUS_STUB_DIR);
        return lastIndexOf(stub, GRADLE_GROOVY_STUB_DIR) >= 0
            || gmaven >= 1 && GENERATED_SOURCES.equals(stub.getName(gmaven - 1).toString());
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
