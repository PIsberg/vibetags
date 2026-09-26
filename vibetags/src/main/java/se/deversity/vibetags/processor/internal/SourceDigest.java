package se.deversity.vibetags.processor.internal;

import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The early exit's key (#834): everything a round's output depends on that can be read before the
 * collection walk, hashed.
 *
 * <p>The fingerprint short-circuit in {@code generateFiles()} needs the walk's result, so on a
 * no-op rebuild it saves almost none of the round's allocation. This key replaces the walk's result
 * with the content of every source the round was given: if no byte of any source changed, the walk
 * would find what it found last time. It is a content hash on purpose, not size and mtime, which a
 * build tool that preserves timestamps or an edit within the same second would pass as unchanged.
 *
 * <p>Everything else that shapes output is in the key too: the processor version, every
 * {@code -A} option, the module and source set, which opt-in files exist and as what kind, at the
 * root and the module root, and the content of every {@code .vibetags-*} configuration file there.
 * Taking in more than output depends on only costs a missed exit; taking in less is a false
 * "unchanged". Sidecars, the write cache and the locks report are outputs or run records with
 * checks of their own, and are left out because they change on every build.
 */
public final class SourceDigest {

    private SourceDigest() {
    }

    /**
     * The files behind this round's root elements, or {@code null} when the round cannot be vouched
     * for: no root elements, no compiler API that maps an element to its file, or any root whose
     * source is not a file on disk (an in-memory source cannot be read back next time).
     */
    public static @Nullable List<Path> sourceFilesOf(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        Trees trees = SourcePositionResolver.treesFor(env);
        Elements elements;
        try {
            elements = env.getElementUtils();
        } catch (RuntimeException | Error unavailable) {
            elements = null;
        }
        if (trees == null && elements == null) {
            return null;
        }
        Set<? extends Element> roots = roundEnv.getRootElements();
        if (roots.isEmpty()) {
            return null;
        }
        Set<Path> files = new LinkedHashSet<>();
        for (Element element : roots) {
            Path file = fileOf(trees, elements, element);
            if (file == null) {
                return null;
            }
            files.add(file);
        }
        return new ArrayList<>(files);
    }

    /**
     * The file {@code element} was declared in, asking {@link Elements#getFileObjectOf} first. That
     * is the reverse of {@code ModuleRootResolver.sourceFileOf}'s order, on purpose: this runs on
     * every root element of every eligible build, cold ones included, and javac answers it from the
     * class symbol where the Tree API builds a path for each element. Measured, the Tree API lookup
     * was most of what the digest allocated.
     */
    private static @Nullable Path fileOf(@Nullable Trees trees, @Nullable Elements elements, Element element) {
        if (elements != null) {
            try {
                javax.tools.JavaFileObject object = elements.getFileObjectOf(element);
                if (object != null) {
                    return onDisk(object.toUri());
                }
            } catch (RuntimeException | Error unavailable) {
                // An older or other compiler: fall back to the shared resolution below.
            }
        }
        return ModuleRootResolver.sourceFileOf(trees, null, element);
    }

    /** A {@code file:} URI as a path, or {@code null} for an in-memory source. */
    private static @Nullable Path onDisk(java.net.URI uri) {
        if (!"file".equals(uri.getScheme())) {
            return null;
        }
        try {
            return Path.of(uri);
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    /**
     * The key, or {@code null} when an input could not be read, in which case the round must not
     * be skipped.
     *
     * @param version         the processor version
     * @param options         every processor option
     * @param moduleId        the module and source set this round compiles
     * @param testRound       whether this round compiles test sources
     * @param root            the VibeTags root
     * @param compilationRoot the compiling module's root; the same as {@code root} for one module
     * @param sourceFiles     the round's source files, in any order
     */
    public static @Nullable String of(String version, Map<String, String> options, String moduleId,
                                      boolean testRound, Path root, Path compilationRoot,
                                      Collection<Path> sourceFiles) {
        MessageDigest sha = sha256();
        // One instance for every file: getInstance does a provider lookup, and per file that was
        // most of what hashing a large round allocated. digest() resets it for the next file.
        MessageDigest fileSha = sha256();
        byte[] buffer = new byte[8192];
        Path base = root.toAbsolutePath().normalize();
        line(sha, "vibetags " + version);
        new TreeMap<>(options).forEach((key, value) -> line(sha, "option " + key + "=" + value));
        line(sha, "module " + moduleId);
        line(sha, "test " + testRound);
        Set<Path> dirs = new LinkedHashSet<>(List.of(base, compilationRoot.toAbsolutePath().normalize()));
        for (Path dir : dirs) {
            line(sha, "dir " + name(base, dir));
            new TreeMap<>(ServiceRegistry.buildServiceFileMap(dir))
                .forEach((key, path) -> line(sha, "opt-in " + key + " " + kindOf(path)));
            List<Path> config = configFiles(dir);
            if (config == null) {
                return null;
            }
            for (Path file : config) {
                String hash = hashOf(fileSha, buffer, file);
                if (hash == null) {
                    return null;
                }
                line(sha, "config " + file.getFileName() + " " + hash);
            }
        }
        Map<String, Path> sources = new TreeMap<>();
        for (Path file : sourceFiles) {
            // Files from sourceFilesOf are already absolute and normalized.
            sources.put(name(base, file.isAbsolute() ? file : file.toAbsolutePath().normalize()), file);
        }
        for (Map.Entry<String, Path> source : sources.entrySet()) {
            String hash = hashOf(fileSha, buffer, source.getValue());
            if (hash == null) {
                return null;
            }
            line(sha, "source " + source.getKey() + " " + hash);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** The {@code .vibetags-*} configuration files directly in {@code dir}, sorted; {@code null} if unlistable. */
    private static @Nullable List<Path> configFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(p -> isConfig(String.valueOf(p.getFileName())))
                .sorted()
                .toList();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * A {@code .vibetags-*} file that configures output, as opposed to a record of a run: the
     * sidecars, the write cache and its temp files, and the locks report, whose presence is an
     * opt-in the service map already covers and whose content is output.
     */
    static boolean isConfig(String name) {
        return name.startsWith(".vibetags-")
            && !name.startsWith(".vibetags-mod-")
            && !name.startsWith(".vibetags-cache")
            && !name.startsWith(ServiceRegistry.LOCKS_REPORT_FILE);
    }

    /**
     * What sits at {@code path}. Most opt-in paths are absent, so that is asked first, by the one
     * call that answers it without throwing; the attributes of the few that exist are read once.
     */
    private static String kindOf(Path path) {
        if (Files.notExists(path)) {
            return "none";
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (attributes.isDirectory()) {
                return "dir";
            }
            return attributes.isRegularFile() ? "file" : "other";
        } catch (IOException | RuntimeException absent) {
            return "none";
        }
    }

    /**
     * {@code path} relative to {@code base}, which the caller has already normalized, or in full when
     * it lies elsewhere. A string prefix rather than {@link Path#relativize}: this runs once per
     * source file, and relativize was half of what the digest allocated.
     */
    private static String name(Path base, Path path) {
        String prefix = base.toString() + base.getFileSystem().getSeparator();
        String full = path.toString();
        String relative = full.startsWith(prefix) ? full.substring(prefix.length()) : full;
        return relative.replace('\\', '/');
    }

    /** SHA-256 of {@code file}, streamed through {@code buffer} rather than read into one array per file. */
    private static @Nullable String hashOf(MessageDigest fileSha, byte[] buffer, Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                fileSha.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(fileSha.digest());
        } catch (IOException | RuntimeException unreadable) {
            fileSha.reset();
            return null;
        }
    }

    private static void line(MessageDigest sha, String text) {
        sha.update(text.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) '\n');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform must provide SHA-256", e);
        }
    }
}
