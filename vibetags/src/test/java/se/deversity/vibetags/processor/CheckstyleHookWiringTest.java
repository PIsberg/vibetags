package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Checkstyle pre-commit hook checks what the build checks, and runs on Windows (#982).
 *
 * <p>Until #982 the hook was {@code gherynos/pre-commit-java}, which runs Checkstyle in Docker with
 * Sun's default ruleset whenever no {@code -c} is passed, and none was. On {@code
 * vibetags-annotations}, which the build's own {@code checkstyle.xml} passes with 0 violations,
 * that ruleset reports 226 errors (225 {@code LineLength}, 1 {@code JavadocPackage}; Checkstyle
 * 13.3.0, 2026-10-23), so wherever the hook ran it rejected clean code. On Windows it never ran:
 * the hook repository ships no {@code .gitattributes}, {@code core.autocrlf=true} checks its scripts
 * out with CRLF, and bash in the container fails on the {@code \r} at every rev.
 */
@DisplayName("The Checkstyle hook runs the build's own Checkstyle")
class CheckstyleHookWiringTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String SCRIPT = "tools/checkstyle-hook.sh";

    @Test
    @DisplayName("no hook runs Checkstyle with a ruleset of its own")
    void noDockerHookWithItsOwnRuleset() throws IOException {
        String config = read(".pre-commit-config.yaml");

        assertFalse(config.contains("repo: https://github.com/gherynos/pre-commit-java"),
            "gherynos/pre-commit-java runs Checkstyle with /sun_checks.xml unless given -c, which "
                + "rejects code the build's checkstyle.xml accepts, and its CRLF scripts cannot run "
                + "on Windows at any rev (#982)");
    }

    @Test
    @DisplayName("the checkstyle hook calls the script through git, on every Java file")
    void hookCallsTheScript() throws IOException {
        String hook = hook(read(".pre-commit-config.yaml"), "checkstyle");

        Matcher entry = Pattern.compile("(?m)^\\s*entry:\\s*(.+?)\\s*$").matcher(hook);
        assertTrue(entry.find(), "the checkstyle hook has no entry:\n" + hook);
        // Same reason as the self-check hook (#905): a bare `bash` resolves through PATH, which on
        // Windows outside Git Bash is WSL's launcher. Git runs `!` aliases with its own shell.
        assertTrue(entry.group(1).startsWith("git ") && entry.group(1).contains("=!bash " + SCRIPT),
            "the checkstyle hook must run " + SCRIPT + " as a git `!` alias (#905):\n" + hook);
        assertTrue(hook.contains("language: system"), "the checkstyle hook must not run in Docker:\n" + hook);
        assertFalse(hook.contains("pass_filenames: false"),
            "the script picks the modules to check from the file names it is passed:\n" + hook);

        Matcher files = Pattern.compile("(?m)^\\s*files:\\s*(\\S+)\\s*$").matcher(hook);
        assertTrue(files.find(), "the checkstyle hook has no files: filter:\n" + hook);
        Pattern filter = Pattern.compile(files.group(1));
        for (String module : checkstyleModules()) {
            String path = module + "/src/main/java/Example.java";
            assertTrue(filter.matcher(path).find(),
                "the checkstyle hook does not fire for " + path + ". Filter: " + files.group(1));
        }
    }

    @Test
    @DisplayName("the script runs the Maven plugin, so the ruleset and version are the build's")
    void scriptRunsTheBuildsCheckstyle() throws IOException {
        Path script = REPO_ROOT.resolve(SCRIPT);
        // Deliberately not assumeTrue on a missing file: a skipped check reads like a passed one.
        assertTrue(Files.isRegularFile(script), SCRIPT + " is missing");
        String text = Files.readString(script, StandardCharsets.UTF_8);

        assertTrue(text.contains("checkstyle:check"),
            SCRIPT + " must run maven-checkstyle-plugin's check goal, which reads each module's "
                + "configLocation (checkstyle.xml) and the plugin's pinned Checkstyle");
        assertTrue(text.contains("maven-checkstyle-plugin"),
            SCRIPT + " must pick modules by whether their pom runs Checkstyle, so a new module is "
                + "covered without editing the hook");
        assertTrue(text.contains("mvn.cmd"),
            SCRIPT + " must prefer mvn.cmd where it exists: Maven dies under Git Bash on Windows");
    }

    /** Every top-level module whose pom runs maven-checkstyle-plugin. */
    private static List<String> checkstyleModules() throws IOException {
        List<String> modules = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(REPO_ROOT, Files::isDirectory)) {
            for (Path dir : dirs) {
                Path pom = dir.resolve("pom.xml");
                if (Files.isRegularFile(pom)
                    && Files.readString(pom, StandardCharsets.UTF_8).contains("<artifactId>maven-checkstyle-plugin</artifactId>")
                    && Files.isDirectory(dir.resolve("src"))) {
                    modules.add(dir.getFileName().toString());
                }
            }
        }
        assertTrue(modules.contains("vibetags") && modules.contains("vibetags-annotations"),
            "expected at least vibetags and vibetags-annotations to run Checkstyle: " + modules);
        return modules;
    }

    private static String hook(String config, String id) {
        int at = config.indexOf("- id: " + id + "\n");
        assertTrue(at >= 0, ".pre-commit-config.yaml has no " + id + " hook");
        int next = config.indexOf("- id:", at + 1);
        int nextRepo = config.indexOf("- repo:", at + 1);
        int end = next < 0 ? config.length() : next;
        if (nextRepo >= 0 && nextRepo < end) {
            end = nextRepo;
        }
        return config.substring(at, end);
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
