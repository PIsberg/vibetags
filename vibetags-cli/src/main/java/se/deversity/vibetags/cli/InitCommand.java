package se.deversity.vibetags.cli;

import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code vibetags init} — activates AI platforms by creating their opt-in files.
 *
 * <p>The processor's contract is that file presence is the user's opt-in signal, and the
 * processor itself never creates an output file. This command is the documented way a user
 * states that intent: {@code --platforms claude,cursor} creates exactly the named files,
 * empty, for the next compile to fill. Keys and paths come from
 * {@link ServiceRegistry}, so the list shown here is the list the processor honours.
 */
final class InitCommand {

    private final PrintStream out;
    private final PrintStream err;
    private final Path dir;

    InitCommand(PrintStream out, PrintStream err, Path dir) {
        this.out = out;
        this.err = err;
        this.dir = dir;
    }

    int run(List<String> args) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(dir);
        Set<String> optIn = ServiceRegistry.optInKeys();

        if (args.contains("--list")) {
            list(serviceFiles, optIn);
            return 0;
        }
        int at = args.indexOf("--platforms");
        if (at < 0 || at + 1 >= args.size()) {
            out.println("Nothing created. Pick platforms first:");
            out.println();
            list(serviceFiles, optIn);
            out.println();
            out.println("then: vibetags init --platforms <key,key,...>");
            return 2;
        }

        List<String> requested = List.of(args.get(at + 1).split(","));
        List<String> unknown = requested.stream()
            .filter(key -> !optIn.contains(key.trim()))
            .toList();
        if (!unknown.isEmpty()) {
            err.println("error: unknown platform key(s): " + String.join(", ", unknown));
            err.println("valid keys come from `vibetags init --list`");
            return 2;
        }

        List<String> created = new ArrayList<>();
        List<String> alreadyActive = new ArrayList<>();
        int refused = 0;
        for (String rawKey : requested) {
            String key = rawKey.trim();
            Path path = serviceFiles.get(key);
            if (Files.exists(path)) {
                alreadyActive.add(key + " (" + dir.relativize(path) + ")");
                continue;
            }
            if (escapesRoot(path)) {
                err.println("error: refusing " + key + " — " + dir.relativize(path)
                    + " resolves outside the project root (symlinked parent?); nothing was created");
                refused++;
                continue;
            }
            try {
                // Granular services opt in with a directory, everything else with a file. The
                // key suffix is the stable, documented convention for that distinction.
                if (key.endsWith("_granular")) {
                    Files.createDirectories(path);
                } else {
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    try {
                        Files.createFile(path);
                    } catch (FileAlreadyExistsException e) {
                        // Appeared between the exists() check and the create: someone else's
                        // opt-in, which is the same thing as already active.
                        alreadyActive.add(key + " (" + dir.relativize(path) + ")");
                        continue;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not create " + path, e);
            }
            created.add(key + " (" + dir.relativize(path) + ")");
        }

        created.forEach(line -> out.println("created:        " + line));
        alreadyActive.forEach(line -> out.println("already active: " + line));
        if (!created.isEmpty()) {
            out.println();
            out.println("Now compile (mvn compile / gradle build) and the processor fills them in.");
            out.println("Processor not wired into the build yet? `vibetags doctor` will tell you.");
        }
        return refused > 0 ? 1 : 0;
    }

    /**
     * True when creating {@code path} would land outside the project root: the deepest
     * ancestor that already exists on disk resolves, symlinks and all, to somewhere the
     * root does not contain. Only components that exist before the create can redirect it,
     * so checking the existing prefix is sufficient — and it runs before anything is
     * written. A checkout can carry a hostile symlink ({@code .github} pointing at an
     * absolute path, say), and init must not follow it out of the tree it was asked to
     * operate on. Unresolvable ancestry (a broken link at the target, an unreadable
     * parent) refuses too rather than guessing.
     */
    private boolean escapesRoot(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return true;
        }
        try {
            return !existing.toRealPath().startsWith(dir.toRealPath());
        } catch (IOException e) {
            return true;
        }
    }

    private void list(Map<String, Path> serviceFiles, Set<String> optIn) {
        out.println("Opt-in platform keys (file presence = opt-in):");
        // TreeMap: stable, scannable order for humans and for tests.
        new TreeMap<>(serviceFiles).forEach((key, path) -> {
            if (optIn.contains(key)) {
                String marker = Files.exists(path) ? "  [active]" : "";
                out.println("  " + key + " -> " + dir.relativize(path) + marker);
            }
        });
    }
}
