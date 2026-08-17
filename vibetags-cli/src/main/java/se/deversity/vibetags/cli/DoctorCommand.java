package se.deversity.vibetags.cli;

import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code vibetags doctor} — reports the project's VibeTags health without compiling anything.
 *
 * <p>Checks, in order: which build tool the directory uses, whether the processor and the
 * annotations artifact are wired into it, which platforms are active (same file-existence
 * resolution the processor runs, via {@link ServiceRegistry}), and whether every active
 * marker file still carries a balanced {@code VIBETAGS-START} / {@code VIBETAGS-END} pair.
 * Exit code 0 means healthy; 1 means at least one finding needs action.
 *
 * <p>A file doctor cannot read (permissions, not UTF-8) is a finding, never a silent pass:
 * "could not check" reported as "checked, fine" is the one lie a health tool must not tell.
 */
final class DoctorCommand {

    private final PrintStream out;
    private final Path dir;
    private final List<String> problems = new ArrayList<>();

    DoctorCommand(PrintStream out, Path dir) {
        this.out = out;
        this.dir = dir;
    }

    int run() {
        out.println("vibetags doctor — " + dir);

        checkWiring(detectBuildFile());
        Set<String> active = checkActivePlatforms();
        checkMarkers(active);

        out.println();
        if (problems.isEmpty()) {
            out.println("result: healthy");
            return 0;
        }
        out.println("result: " + problems.size() + " finding(s) need action:");
        problems.forEach(p -> out.println("  - " + p));
        return 1;
    }

    /** The build file doctor will grep for wiring, or empty when none is recognised. */
    private Optional<String> detectBuildFile() {
        for (String candidate : new String[]{"pom.xml", "build.gradle", "build.gradle.kts"}) {
            if (Files.isRegularFile(dir.resolve(candidate))) {
                out.println("build tool:      " + candidate);
                return Optional.of(candidate);
            }
        }
        out.println("build tool:      none recognised (no pom.xml or build.gradle[.kts])");
        problems.add("no build file found — doctor can only check opt-in files here");
        return Optional.empty();
    }

    private void checkWiring(Optional<String> detected) {
        if (detected.isEmpty()) {
            return;
        }
        String buildFile = detected.get();
        Optional<String> read = tryRead(dir.resolve(buildFile));
        if (read.isEmpty()) {
            out.println("processor wired: unknown — could not read " + buildFile);
            out.println("annotations dep: unknown — could not read " + buildFile);
            problems.add("could not read " + buildFile + " (permissions? not UTF-8?) — "
                + "doctor cannot verify the build wiring");
            return;
        }
        String text = read.get();
        boolean processor = text.contains("vibetags-processor");
        boolean annotations = text.contains("vibetags-annotations");
        out.println("processor wired: " + (processor ? "yes" : "NO — not found in " + buildFile));
        out.println("annotations dep: " + (annotations ? "yes" : "NO — not found in " + buildFile));
        if (!processor) {
            problems.add("vibetags-processor is not in " + buildFile
                + " — nothing regenerates the guardrail files (see the README install snippet)");
        }
        if (!annotations) {
            problems.add("vibetags-annotations is not in " + buildFile
                + " — the @AI* annotations will not compile");
        }
    }

    private Set<String> checkActivePlatforms() {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(dir);
        Set<String> active = new TreeSet<>(ServiceRegistry.resolveActiveServices(serviceFiles));
        if (active.isEmpty()) {
            out.println("active platforms: none");
            problems.add("no opt-in files present — run `vibetags init --platforms <key,...>` "
                + "(file presence is the opt-in; the processor never creates files itself)");
            return active;
        }
        out.println("active platforms (" + active.size() + "):");
        active.forEach(key -> out.println("  " + key + " -> " + dir.relativize(serviceFiles.get(key))));

        // Mirror of the processor's AGENTS.md sole-file rule, so doctor explains the one case
        // that reliably surprises people: the file exists but is deliberately not managed.
        Path agents = serviceFiles.get("codex");
        if (agents != null && Files.exists(agents) && !active.contains("codex")) {
            out.println("note: AGENTS.md exists but is treated as a hand-authored pointer "
                + "(other AI config files are present); paste a VIBETAGS-START/END pair into it "
                + "to have VibeTags manage it");
        }
        return active;
    }

    /**
     * A marker file with a START but no END (or the reverse) is how a half-lost merge or a
     * hand edit silently turns partial regeneration into whole-file confusion — the exact
     * state the writer refuses to touch. Only balanced-or-absent passes, and only a file
     * that could actually be read counts as checked.
     */
    private void checkMarkers(Set<String> active) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(dir);
        int broken = 0;
        for (String key : active) {
            // active is resolveActiveServices(serviceFiles), so it can only name keys this map
            // has. ServiceRegistryKeyParityTest pins the wider version of the same invariant.
            Path path = Objects.requireNonNull(serviceFiles.get(key),
                "active service " + key + " has no path in buildServiceFileMap");
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Optional<String> read = tryRead(path);
            if (read.isEmpty()) {
                broken++;
                problems.add("could not read " + dir.relativize(path)
                    + " (permissions? not UTF-8?) — marker state unknown, and the writer must "
                    + "be able to read this file to preserve hand-authored content around the block");
                continue;
            }
            String text = read.get();
            boolean brokenMd = text.contains(GuardrailFileWriter.MARKER_START_MD)
                != text.contains(GuardrailFileWriter.MARKER_END_MD);
            boolean brokenHash = text.contains(GuardrailFileWriter.MARKER_START_HASH)
                != text.contains(GuardrailFileWriter.MARKER_END_HASH);
            if (brokenMd || brokenHash) {
                broken++;
                problems.add("unbalanced VIBETAGS markers in " + dir.relativize(path)
                    + " — restore the missing marker (or delete both and recompile) before "
                    + "the next build, or hand-authored content around the block is at risk");
            }
        }
        out.println("markers:         "
            + (broken == 0 ? "all intact" : broken + " file(s) unbalanced or unreadable"));
    }

    private static Optional<String> tryRead(Path path) {
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
