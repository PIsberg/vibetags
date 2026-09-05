package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Cross-module mirroring of granular rules, read from a {@code .vibetags-mirror} file in the module
 * that wants to <em>receive</em> them (issue #312).
 *
 * <p>Guardrails are otherwise scoped to the module that owns the annotated source. A reactor that
 * centralises its tests in a separate module therefore leaves those tests with no rules at all: the
 * guardrails live in a sibling, not an ancestor, so a host tool that discovers rule directories by
 * walking up from the edited file finds nothing — silently. This config closes that gap the same way
 * every other VibeTags output is opted into: by the presence of a file on disk.
 *
 * <p>The <em>target</em> declares the relationship, not the producer. A test module usually has no
 * {@code @AI*} annotations of its own (correctly so) and knows exactly which modules it exercises,
 * whereas requiring every annotated module to name its consumers scales with the wrong number.
 *
 * <p>File format — one entry per line, {@code #} comments and blank lines ignored:
 * <pre>
 * # Source modules to mirror from, relative to this file's directory.
 * # No source lines at all = mirror from every module in the reactor.
 * ../payments-core
 * ../payments-api
 *
 * # Globs appended to every mirrored rule file's frontmatter, so the mirrored rules
 * # actually match this module's sources. Defaults to **&#47;&lt;this-dir&gt;&#47;**&#47;*.java
 * glob = **&#47;payments-tests&#47;**&#47;*.java
 * </pre>
 *
 * <p>Mirrored files are written into the target's own granular rule directories (whichever it has
 * opted into) under a reserved {@value MirrorWriter#MIRROR_PREFIX} filename prefix, namespaced per
 * source module, so concurrently compiling modules never clean up each other's output.
 */
public final class MirrorConfig {

    /** Opt-in signal file: its presence in a module directory makes that module a mirror target. */
    public static final String FILE_NAME = ".vibetags-mirror";

    /** Directory names never descended into while discovering mirror targets. */
    static final Set<String> SKIP_DIRS = Set.of(
        "target", "build", "out", "bin", "src", "node_modules", ".git", ".idea", ".mvn", ".gradle");

    /** How far below the VibeTags root a mirror target may sit (covers {@code root/group/module}). */
    private static final int MAX_DEPTH = 2;

    private final Path targetDir;
    private final List<Path> sources;
    private final List<String> globs;
    private final String contentHash;

    private MirrorConfig(Path targetDir, List<Path> sources, List<String> globs, String contentHash) {
        this.targetDir = targetDir;
        this.sources = sources;
        this.globs = globs;
        this.contentHash = contentHash;
    }

    /** The module directory that receives mirrored rules (the directory holding the config file). */
    public Path targetDir() {
        return targetDir;
    }

    /** The config file itself — watched so an edit invalidates the build fingerprint's file check. */
    public Path configFile() {
        return targetDir.resolve(FILE_NAME);
    }

    /** Globs appended to every mirrored rule file's frontmatter. Never empty. */
    public List<String> globs() {
        // Already immutable (List.copyOf at construction); copyOf returns it as-is, and the explicit
        // call keeps the getter from reading as a mutable-internals leak.
        return List.copyOf(globs);
    }

    /** 8-hex hash of the raw config content, for folding into cache/fingerprint state. */
    public String contentHash() {
        return contentHash;
    }

    /**
     * True when {@code moduleRoot}'s guardrails should be mirrored here: either the config names no
     * sources at all (mirror everything), or it names this module. A module never mirrors into
     * itself, whatever the config says.
     */
    public boolean accepts(Path moduleRoot) {
        if (moduleRoot == null) {
            return false;
        }
        Path candidate = moduleRoot.toAbsolutePath().normalize();
        if (candidate.equals(targetDir)) {
            return false;
        }
        return sources.isEmpty() || sources.contains(candidate);
    }

    /**
     * Loads {@code <dir>/.vibetags-mirror} if present, else returns {@code null} (this directory is
     * not a mirror target). Unreadable files are treated as absent.
     */
    public static @Nullable MirrorConfig load(Path dir) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        Path target = dir.toAbsolutePath().normalize();
        List<Path> sources = new ArrayList<>();
        List<String> globs = new ArrayList<>();
        for (String raw : content.split("\r\n|\r|\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String glob = stripGlobKey(line);
            if (glob != null) {
                if (!glob.isEmpty() && !globs.contains(glob)) {
                    globs.add(glob);
                }
                continue;
            }
            Path source = target.resolve(line).normalize();
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }
        if (globs.isEmpty()) {
            globs.add(defaultGlob(target));
        }
        return new MirrorConfig(target, List.copyOf(sources), List.copyOf(globs),
            BuildFingerprint.fingerprint(content));
    }

    /**
     * Finds every mirror target at or below {@code root}, to a bounded depth. Returns an empty list
     * when the feature is unused, which is the overwhelmingly common case — the walk short-circuits
     * on build-output and source directories so an unconfigured reactor pays only a shallow listing.
     */
    public static List<MirrorConfig> discover(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<MirrorConfig> found = new ArrayList<>();
        collect(root.toAbsolutePath().normalize(), 0, found, new LinkedHashSet<>());
        return Collections.unmodifiableList(found);
    }

    private static void collect(Path dir, int depth, List<MirrorConfig> out, Set<Path> seen) {
        if (!seen.add(dir)) {
            return; // symlink cycle guard
        }
        if (depth > 0) {
            MirrorConfig cfg = load(dir);
            if (cfg != null) {
                out.add(cfg);
            }
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> dirs = children
                .filter(Files::isDirectory)
                .filter(p -> {
                    Path name = p.getFileName();
                    return name != null && !SKIP_DIRS.contains(name.toString());
                })
                .sorted()
                .toList();
            for (Path child : dirs) {
                collect(child, depth + 1, out, seen);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable directory — mirroring is best-effort and must never fail a compile.
        }
    }

    /**
     * Recognises a {@code glob = ...} / {@code glob: ...} / {@code globs = ...} directive, returning
     * the value (possibly empty) or {@code null} when the line is a source path instead.
     */
    private static @Nullable String stripGlobKey(String line) {
        int sep = indexOfAny(line, '=', ':');
        if (sep < 0) {
            return null;
        }
        String key = line.substring(0, sep).trim().toLowerCase(java.util.Locale.ROOT);
        if (!"glob".equals(key) && !"globs".equals(key)) {
            return null;
        }
        return line.substring(sep + 1).trim();
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    /** Default frontmatter glob for a target: every Java source under the target's directory name. */
    private static String defaultGlob(Path target) {
        Path name = target.getFileName();
        return "**/" + (name != null ? name.toString() : "**") + "/**/*.java";
    }
}
