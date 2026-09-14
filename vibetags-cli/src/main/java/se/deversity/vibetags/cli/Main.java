package se.deversity.vibetags.cli;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Entry point for the VibeTags companion CLI.
 *
 * <p>Two commands, both thin by design: {@code init} creates the opt-in files whose presence
 * the annotation processor honours, and {@code doctor} reports the project's VibeTags health.
 * Everything platform-specific — the opt-in key list, the file paths, the marker strings —
 * is read from {@code vibetags-processor} so this tool cannot drift from what the processor
 * actually does.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, utf8(System.out), utf8(System.err), Path.of("").toAbsolutePath()));
    }

    /**
     * A console stream that always writes UTF-8. {@code System.out} encodes with the platform
     * console charset, which on Windows is Cp1252 whenever stdout is a pipe or a file, so the
     * em-dashes in doctor output arrived in CI logs as a single stray byte. Wrapping the
     * existing stream keeps the bytes this layer produces untouched: a PrintStream passes
     * {@code write(byte[])} through without re-encoding.
     */
    static PrintStream utf8(OutputStream raw) {
        return new PrintStream(raw, true, StandardCharsets.UTF_8);
    }

    /**
     * Testable dispatch: parses the global {@code --dir} option, routes to the command, and
     * returns the process exit code (0 = success/healthy, 1 = problems found, 2 = usage error).
     */
    static int run(String[] args, PrintStream out, PrintStream err, Path defaultDir) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        Path dir = defaultDir;
        int dirAt = rest.indexOf("--dir");
        if (dirAt >= 0) {
            if (dirAt + 1 >= rest.size()) {
                err.println("error: --dir needs a path");
                return 2;
            }
            try {
                dir = Path.of(rest.get(dirAt + 1)).toAbsolutePath();
            } catch (InvalidPathException e) {
                err.println("error: --dir is not a valid path: " + e.getMessage());
                return 2;
            }
            rest.remove(dirAt + 1);
            rest.remove(dirAt);
        }
        if (rest.isEmpty() || "--help".equals(rest.get(0)) || "-h".equals(rest.get(0))) {
            usage(out);
            return rest.isEmpty() ? 2 : 0;
        }
        if (!Files.isDirectory(dir)) {
            err.println("error: not a directory: " + dir);
            return 2;
        }
        String command = rest.remove(0);
        try {
            return switch (command) {
                case "init" -> new InitCommand(out, err, dir).run(rest);
                case "doctor" -> {
                    Optional<List<Path>> classpath = doctorClasspath(rest, err);
                    if (classpath.isEmpty()) {
                        yield 2;
                    }
                    if (!rest.isEmpty()) {
                        /* A stray argument used to be ignored, so "doctor /other/project" quietly
                           reported on the current directory instead. */
                        err.println("error: doctor takes no arguments (use --dir <path>, "
                            + "--classpath <entries>): " + String.join(" ", rest));
                        yield 2;
                    }
                    yield new DoctorCommand(out, dir, classpath.get()).run();
                }
                case "--version", "version" -> {
                    out.println("vibetags-cli " + version());
                    yield 0;
                }
                default -> {
                    err.println("error: unknown command '" + command + "'");
                    usage(err);
                    yield 2;
                }
            };
        } catch (UncheckedIOException e) {
            err.println("error: " + e.getMessage()
                + (e.getCause() != null ? ": " + e.getCause().getMessage() : ""));
            return 1;
        }
    }

    /**
     * Removes {@code --classpath <entries>} from {@code rest} and returns its entries (none when the
     * option is absent), or empty after printing why the value is unusable. Entries are separated by
     * the platform path separator, like {@code java -cp}, so a Maven or Gradle compile classpath can
     * be passed as printed (#691).
     */
    private static Optional<List<Path>> doctorClasspath(List<String> rest, PrintStream err) {
        List<Path> entries = new ArrayList<>();
        int at = rest.indexOf("--classpath");
        if (at < 0) {
            return Optional.of(entries);
        }
        if (at + 1 >= rest.size()) {
            err.println("error: --classpath needs jars or class directories, separated by '"
                + File.pathSeparator + "'");
            return Optional.empty();
        }
        for (String entry : Pattern.compile(Pattern.quote(File.pathSeparator)).splitAsStream(rest.get(at + 1)).toList()) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                entries.add(Path.of(entry.strip()).toAbsolutePath());
            } catch (InvalidPathException e) {
                err.println("error: --classpath entry is not a valid path: " + e.getMessage());
                return Optional.empty();
            }
        }
        rest.remove(at + 1);
        rest.remove(at);
        return Optional.of(entries);
    }

    /** The jar's Implementation-Version, or a placeholder when run from unpackaged classes. */
    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "(unreleased)";
    }

    private static void usage(PrintStream to) {
        to.println("""
            vibetags — companion CLI for the VibeTags annotation processor

            Usage:
              vibetags init --list                     list every opt-in platform key
              vibetags init --platforms claude,cursor  activate platforms (creates their opt-in files)
              vibetags doctor                          report the project's VibeTags health
              vibetags doctor --classpath <entries>    also read Kotlin value classes from these jars
                                                       and class directories (a compile classpath)
              vibetags --version                       print the CLI version

            Options:
              --dir <path>   project root to operate on (default: current directory)

            File presence is the opt-in signal: the processor only regenerates files that already
            exist. `init` creates them because you asked; the processor itself never will.""");
    }
}
