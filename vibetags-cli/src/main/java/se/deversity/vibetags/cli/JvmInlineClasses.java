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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * dependency. A deprecated {@code inline class}, written without {@code @JvmInline}, carries the
 * annotation all the same when kotlinc 2.4.10 compiles it ({@code javap -v}), so it needs nothing
 * more. A nested value class ({@code Outer$Id}) is named as the source writes it,
 * {@code Outer.Id}, through the class file's {@code InnerClasses} attribute
 * (<a href="https://github.com/PIsberg/vibetags/issues/714">#714</a>); a local or anonymous class
 * has no such name and is not counted.
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

    /** A class file outside META-INF; nested ones included, since a value class can be nested (#714). */
    private static boolean isCandidate(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/")
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
     * The name of the class in {@code bytes}, as source refers to it ({@code com.acme.Outer.Id} for the
     * binary {@code com/acme/Outer$Id}), when its class-level {@code RuntimeVisibleAnnotations} include
     * {@code @kotlin.jvm.JvmInline}; empty otherwise, and for a local or anonymous class.
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
        Map<Integer, int[]> nesting = new HashMap<>();
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String name = at(utf8, in.readUnsignedShort());
            int length = in.readInt();
            if (length < 0) {
                throw new IOException("attribute length out of range");
            }
            if ("RuntimeVisibleAnnotations".equals(name)) {
                inline |= annotatedJvmInline(new DataInputStream(new ByteArrayInputStream(in.readNBytes(length))), utf8);
            } else if ("InnerClasses".equals(name)) {
                readInnerClasses(new DataInputStream(new ByteArrayInputStream(in.readNBytes(length))), nesting);
            } else {
                in.skipNBytes(length);
            }
        }
        if (!inline) {
            return Optional.empty();
        }
        return sourceName(thisClass, nesting, classNames, utf8, 0);
    }

    /** Records each InnerClasses entry as inner class index to {outer class index, simple name index}. */
    private static void readInnerClasses(DataInputStream in, Map<Integer, int[]> nesting) throws IOException {
        int classes = in.readUnsignedShort();
        for (int i = 0; i < classes; i++) {
            int inner = in.readUnsignedShort();
            int outer = in.readUnsignedShort();
            int simpleName = in.readUnsignedShort();
            in.skipNBytes(2);   // access flags
            nesting.put(inner, new int[]{outer, simpleName});
        }
    }

    /**
     * The name source code uses for the class constant at {@code index}: the binary name for a
     * top-level class, the enclosing class's source name plus the simple name for a member class, and
     * empty for a local or anonymous class, whose entry names no outer class.
     */
    private static Optional<String> sourceName(int index, Map<Integer, int[]> nesting, int[] classNames,
                                               String[] utf8, int depth) throws IOException {
        if (depth > MAX_NESTING) {
            throw new IOException("class nested deeper than " + MAX_NESTING);
        }
        if (index <= 0 || index >= classNames.length || classNames[index] == 0) {
            throw new IOException("class constant index " + index + " out of range");
        }
        int[] entry = nesting.get(index);
        if (entry == null) {
            return Optional.of(at(utf8, classNames[index]).replace('/', '.'));
        }
        if (entry[0] == 0 || entry[1] == 0) {
            return Optional.empty();
        }
        String simpleName = at(utf8, entry[1]);
        return sourceName(entry[0], nesting, classNames, utf8, depth + 1).map(outer -> outer + "." + simpleName);
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
