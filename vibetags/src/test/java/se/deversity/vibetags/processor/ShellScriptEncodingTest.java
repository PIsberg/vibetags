package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * No tracked shell script may begin with a UTF-8 byte order mark.
 *
 * <p>A BOM sits in front of the shebang, so line 1 is no longer a comment. Run through
 * {@code bash script.sh} the interpreter treats those three bytes plus {@code #!/usr/bin/env} as a
 * command, fails to find it, and carries on with the rest of the file; run through the shebang it
 * fails outright. The first form is the dangerous one, because the script still does its work and
 * the step still passes, so the error sits in the log indefinitely.
 *
 * <p>That is exactly what happened: {@code examples/basic/reset-ai-files.sh} carried a BOM and
 * every CI run logged {@code reset-ai-files.sh: line 1: #!/usr/bin/env: No such file or directory}
 * while reporting success. An editor writing UTF-8-with-signature is all it takes to reintroduce
 * it, which is why this is a test rather than a one-off fix.
 */
class ShellScriptEncodingTest {

    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void noTrackedShellScriptStartsWithAByteOrderMark() throws Exception {
        List<String> tracked = trackedFiles();
        assumeTrue(!tracked.isEmpty(), "git did not list any tracked files; skipping");

        List<String> scripts = tracked.stream().filter(f -> f.endsWith(".sh")).toList();
        assumeTrue(!scripts.isEmpty(), "no tracked shell scripts; skipping");

        List<String> offenders = new ArrayList<>();
        for (String script : scripts) {
            Path file = REPO_ROOT.resolve(script);
            if (!Files.isRegularFile(file)) {
                continue; // Listed but not checked out (sparse checkout, or a submodule path).
            }
            byte[] head = readFirstBytes(file, UTF8_BOM.length);
            if (java.util.Arrays.equals(head, UTF8_BOM)) {
                offenders.add(script);
            }
        }

        assertTrue(offenders.isEmpty(),
            "these shell scripts start with a UTF-8 BOM, which puts three bytes in front of the "
                + "shebang and makes line 1 fail as a command instead of being read as a comment. "
                + "Re-save them as UTF-8 without a signature: " + String.join(", ", offenders));
    }

    private static byte[] readFirstBytes(Path file, int count) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(count);
        }
    }

    private static List<String> trackedFiles() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "ls-files");
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> files = new ArrayList<>();
        try (Stream<String> lines = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).lines()) {
            lines.map(String::trim).filter(s -> !s.isEmpty()).forEach(files::add);
        }
        p.waitFor();
        return files;
    }
}
