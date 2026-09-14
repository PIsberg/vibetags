package se.deversity.vibetags.cli;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the names of Kotlin value classes out of compiled class files, so that doctor can resolve a
 * value class declared in a dependency or in a module outside {@code --dir}
 * (<a href="https://github.com/PIsberg/vibetags/issues/691">#691</a>).
 *
 * <p>kotlinc writes {@code @kotlin.jvm.JvmInline} into a value class's class-level
 * {@code RuntimeVisibleAnnotations} attribute ({@code javap -v} on a Kotlin 2.4.10 value class), so
 * the constant pool and the class attributes are all this needs: no Kotlin metadata decoder and no
 * dependency. A deprecated {@code inline class} compiled without {@code @JvmInline} is not seen, and
 * neither is a nested value class ({@code Outer$Id}), which the source scan does not resolve either.
 */
final class JvmInlineClasses {

    private static final String JVM_INLINE = "Lkotlin/jvm/JvmInline;";
    private static final byte[] JVM_INLINE_BYTES = "kotlin/jvm/JvmInline".getBytes(StandardCharsets.US_ASCII);

    /** Deeper than any annotation a compiler writes; a crafted class file cannot exhaust the stack. */
    private static final int MAX_NESTING = 64;

    /** Larger than any class file a compiler emits; a bigger entry is not read. */
    private static final int MAX_CLASS_BYTES = 16 << 20;

    /** Value classes found, entries or class files that could not be read, and how many classes were read. */
    record Result(Set<String> valueClasses, List<String> problems, int classFiles) {
    }

    private JvmInlineClasses() {
    }

    static Result read(List<Path> entries) {
        Set<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        int[] classFiles = {0};
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                readDirectory(entry, found, problems, classFiles);
            } else if (Files.exists(entry)) {
                readArchive(entry, found, problems, classFiles);
            } else {
                problems.add("classpath entry " + entry + " does not exist: value classes declared in it cannot be seen");
            }
        }
        return new Result(found, problems, classFiles[0]);
    }

    private static void readDirectory(Path root, Set<String> found, List<String> problems, int[] classFiles) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (isCandidate(name)) {
                    classFiles[0]++;
                    classify(root + ": " + name, Files.readAllBytes(file), found, problems);
                }
            }
        } catch (IOException e) {
            problems.add("could not read classpath directory " + root + ": " + e.getMessage());
        }
    }

    private static void readArchive(Path archive, Set<String> found, List<String> problems, int[] classFiles) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (entry.isDirectory() || !isCandidate(entry.getName())) {
                    continue;
                }
                classFiles[0]++;
                try (InputStream in = zip.getInputStream(entry)) {
                    classify(archive + "!/" + entry.getName(), in.readNBytes(MAX_CLASS_BYTES), found, problems);
                }
            }
        } catch (IOException e) {
            problems.add("could not read classpath entry " + archive + " (not a jar or a directory?): "
                + e.getMessage());
        }
    }

    /** A top-level class file outside META-INF: the only shape a resolvable value class can have. */
    private static boolean isCandidate(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/") && name.indexOf('$') < 0
            && !name.endsWith("module-info.class") && !name.endsWith("package-info.class");
    }

    private static void classify(String where, byte[] bytes, Set<String> found, List<String> problems) {
        if (!contains(bytes, JVM_INLINE_BYTES)) {
            return;   // cannot carry the annotation: not worth parsing
        }
        try {
            valueClassName(bytes).filter(name -> !"kotlin.Result".equals(name)).ifPresent(found::add);
        } catch (IOException e) {
            problems.add("could not read class file " + where + " (" + e.getMessage()
                + "): a value class declared in it cannot be seen");
        }
    }

    /**
     * The binary name of the class in {@code bytes} when its class-level
     * {@code RuntimeVisibleAnnotations} include {@code @kotlin.jvm.JvmInline}, else empty.
     *
     * @throws IOException when the bytes are not a well-formed class file
     */
    static Optional<String> valueClassName(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.skipNBytes(4);   // minor and major version
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNames = new int[count];
        int i = 1;
        while (i < count) {
            int tag = in.readUnsignedByte();
            int slots = 1;
            switch (tag) {
                case 1 -> {
                    utf8[i] = in.readUTF();
                }
                case 7 -> {
                    classNames[i] = in.readUnsignedShort();
                }
                case 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    slots = 2;   // a long or double takes two constant pool slots
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
            i += slots;
        }
        in.skipNBytes(2);   // access flags
        int thisClass = in.readUnsignedShort();
        in.skipNBytes(2);   // super class
        in.skipNBytes(2L * in.readUnsignedShort());   // interfaces
        skipMembers(in);    // fields
        skipMembers(in);    // methods
        boolean inline = false;
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String name = at(utf8, in.readUnsignedShort());
            int length = in.readInt();
            if (length < 0) {
                throw new IOException("attribute length out of range");
            }
            if ("RuntimeVisibleAnnotations".equals(name)) {
                inline |= annotatedJvmInline(new DataInputStream(new ByteArrayInputStream(in.readNBytes(length))), utf8);
            } else {
                in.skipNBytes(length);
            }
        }
        if (!inline) {
            return Optional.empty();
        }
        if (thisClass <= 0 || thisClass >= count) {
            throw new IOException("this_class out of range");
        }
        return Optional.of(at(utf8, classNames[thisClass]).replace('/', '.'));
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int members = in.readUnsignedShort();
        for (int m = 0; m < members; m++) {
            in.skipNBytes(6);   // access flags, name, descriptor
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.skipNBytes(2);
                int length = in.readInt();
                if (length < 0) {
                    throw new IOException("attribute length out of range");
                }
                in.skipNBytes(length);
            }
        }
    }

    /** Whether the top-level annotations of a RuntimeVisibleAnnotations body include {@code @JvmInline}. */
    private static boolean annotatedJvmInline(DataInputStream in, String[] utf8) throws IOException {
        boolean inline = false;
        int annotations = in.readUnsignedShort();
        for (int i = 0; i < annotations; i++) {
            inline |= JVM_INLINE.equals(annotation(in, utf8, 0));
        }
        return inline;
    }

    /** Reads one annotation structure and returns its type descriptor. */
    private static String annotation(DataInputStream in, String[] utf8, int depth) throws IOException {
        if (depth > MAX_NESTING) {
            throw new IOException("annotation nested deeper than " + MAX_NESTING);
        }
        String type = at(utf8, in.readUnsignedShort());
        int pairs = in.readUnsignedShort();
        for (int p = 0; p < pairs; p++) {
            in.skipNBytes(2);   // element name
            skipElementValue(in, utf8, depth + 1);
        }
        return type;
    }

    private static void skipElementValue(DataInputStream in, String[] utf8, int depth) throws IOException {
        if (depth > MAX_NESTING) {
            throw new IOException("annotation nested deeper than " + MAX_NESTING);
        }
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.skipNBytes(2);
            case 'e' -> in.skipNBytes(4);
            case '@' -> annotation(in, utf8, depth + 1);
            case '[' -> {
                int values = in.readUnsignedShort();
                for (int v = 0; v < values; v++) {
                    skipElementValue(in, utf8, depth + 1);
                }
            }
            default -> throw new IOException("unknown element value tag " + tag);
        }
    }

    private static String at(String[] utf8, int index) throws IOException {
        String value = index > 0 && index < utf8.length ? utf8[index] : null;
        if (value == null) {
            throw new IOException("constant pool index " + index + " is not a UTF-8 entry");
        }
        return value;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }
}
