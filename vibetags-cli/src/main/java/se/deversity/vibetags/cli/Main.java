package se.deversity.vibetags.cli;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        System.exit(run(args, System.out, System.err, Path.of("").toAbsolutePath()));
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
        String command = rest.remove(0);
        try {
            return switch (command) {
                case "init" -> new InitCommand(out, err, dir).run(rest);
                case "doctor" -> new DoctorCommand(out, dir).run();
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
              vibetags --version                       print the CLI version

            Options:
              --dir <path>   project root to operate on (default: current directory)

            File presence is the opt-in signal: the processor only regenerates files that already
            exist. `init` creates them because you asked; the processor itself never will.""");
    }
}
