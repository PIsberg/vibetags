package se.deversity.vibetags.processor.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The committed record of what enforced elements looked like when they were last approved.
 *
 * <p>File: {@code <root>/.vibetags-baseline}, one tab-separated line per enforced element:
 * <pre>{@code
 * <moduleId>\t<family>\t<element path>\t<signature>
 * }</pre>
 *
 * <p>Signatures are stored in full rather than hashed, and the file is sorted, so a pull request
 * that changes a guarded API shows <em>what</em> changed in its own diff. A hash would make the file
 * smaller and the review worthless.
 *
 * <p><strong>Module ownership is part of the key</strong>, because every module of a reactor writes
 * to this one file from its own javac invocation. Updating rewrites only the lines belonging to the
 * compiling module and leaves its siblings' alone — the same merge discipline as the sidecars, and
 * for the same reason: without it the last module to compile would silently erase the rest
 * (issues #278, #330).
 */
public final class EnforcementBaseline {

    static final String FILE_NAME = ".vibetags-baseline";
    private static final String HEADER =
        "# VibeTags enforcement baseline — regenerate with -Avibetags.baseline.update=true\n"
        + "# Lines are <moduleId>\\t<family>\\t<element>\\t<signature>, sorted; review changes here as\n"
        + "# you would any other API change.\n"
        + "# format: 1\n";
    private static final String FORMAT_MARKER = "# format: 1";

    /** key = moduleId + '\t' + family + '\t' + path, value = signature. */
    private final Map<String, String> entries;

    private EnforcementBaseline(Map<String, String> entries) {
        this.entries = entries;
    }

    /** Reads the baseline at {@code root}, or an empty one when absent or unreadable. */
    public static EnforcementBaseline load(Path root) {
        Map<String, String> entries = new LinkedHashMap<>();
        Path file = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new EnforcementBaseline(entries);
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int lastTab = line.lastIndexOf('\t');
                if (lastTab < 0) {
                    continue;
                }
                entries.put(line.substring(0, lastTab), line.substring(lastTab + 1));
            }
        } catch (IOException | RuntimeException e) {
            return new EnforcementBaseline(new LinkedHashMap<>());
        }
        return new EnforcementBaseline(entries);
    }

    /** True when the file exists and carries a format header this processor understands. */
    public static boolean exists(Path root) {
        Path file = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                        .anyMatch(FORMAT_MARKER::equals);
        } catch (IOException e) {
            return false;
        }
    }

    /** The approved signature for {@code path} under {@code family}, or {@code null} if unrecorded. */
    public String signatureFor(String moduleId, String family, String path) {
        return entries.get(key(moduleId, family, path));
    }

    /** True when this baseline records nothing at all for {@code moduleId}. */
    public boolean hasNothingFor(String moduleId) {
        String prefix = moduleId + "\t";
        return entries.keySet().stream().noneMatch(k -> k.startsWith(prefix));
    }

    /**
     * Every {@code family\tpath} this baseline approved for {@code moduleId} under {@code families},
     * so the caller can spot approved elements that the compilation no longer contains.
     *
     * <p>That direction is the one that matters most: an element's path already encodes its
     * parameter types, so changing a method's signature does not edit an entry — it abandons one
     * and creates another. Checking only "did an entry's value change" would miss precisely the
     * breakage {@code @AIContract} exists to prevent.
     */
    public Set<String> approvedFor(String moduleId, Set<String> families) {
        String prefix = moduleId + "\t";
        Set<String> approved = new LinkedHashSet<>();
        for (String key : entries.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String familyAndPath = key.substring(prefix.length());
            int tab = familyAndPath.indexOf('\t');
            if (tab > 0 && families.contains(familyAndPath.substring(0, tab))) {
                approved.add(familyAndPath);
            }
        }
        return approved;
    }

    /**
     * Rewrites the baseline, replacing every line owned by {@code moduleId} with {@code current}
     * and preserving every sibling module's. Written atomically and sorted.
     *
     * @param current family + path → signature for the compiling module
     */
    public void update(Path root, String moduleId, Map<String, String> current) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(entries);
        String prefix = moduleId + "\t";
        merged.keySet().removeIf(k -> k.startsWith(prefix));
        current.forEach((familyAndPath, signature) -> merged.put(prefix + familyAndPath, signature));

        List<String> lines = new ArrayList<>(merged.size());
        merged.forEach((k, v) -> lines.add(k + "\t" + v));
        Collections.sort(lines);

        StringBuilder sb = new StringBuilder(HEADER);
        lines.forEach(line -> sb.append(line).append('\n'));

        Path target = root.resolve(FILE_NAME);
        Path tmp = root.resolve(FILE_NAME + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        entries.clear();
        entries.putAll(merged);
    }

    /** The composite key used in the file; also the shape {@link #update} expects, minus the module. */
    public static String familyAndPath(String family, String path) {
        return family + "\t" + path;
    }

    private static String key(String moduleId, String family, String path) {
        return moduleId + "\t" + familyAndPath(family, path);
    }
}
