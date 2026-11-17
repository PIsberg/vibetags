package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warnings name files and modules with forward slashes on every OS (#1101).
 *
 * <p>A path in a diagnostic is read by people on several machines and grepped for in build logs,
 * so it is spelled the way sidecars, the write cache and generated files spell it. Each case puts
 * the named file or module one directory down, which is where a Windows build would print a
 * backslash; on Linux and macOS the native separator already is a slash, so these cases can only
 * fail on Windows (the windows-latest CI job and Windows workstations).
 */
@Tag("e2e")
class DiagnosticPathSeparatorTest {

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aFileOptedIntoAfterASiblingCompiledIsNamedWithForwardSlashes(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        ProcessorTestHarness alpha = module(root, "module-a", "com.example.a", "Alpha");
        warnings(alpha);
        warnings(module(root, "module-b", "com.example.b", "Beta"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        Files.createDirectories(root.resolve(".github"));
        Files.createFile(root.resolve(".github/copilot-instructions.md"));

        List<String> warnings = warnings(alpha);

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".github/copilot-instructions.md was opted into after")),
            "the opted-in file is named with a forward slash: " + warnings);
    }

    @Test
    void aModuleWhoseIdCollidesIsNamedWithForwardSlashes(@TempDir Path root) throws Exception {
        // group/core and group_core both resolve to the module id group_core.
        Files.createFile(root.resolve("CLAUDE.md"));
        warnings(module(root, "group_core", "com.example.flat", "Flat"));

        List<String> warnings = warnings(module(root, "group/core", "com.example.nested", "Nested"));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("modules 'group/core' and")),
            "the compiling module is named with a forward slash: " + warnings);
    }

    private static ProcessorTestHarness module(Path root, String module, String pkg, String type) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module.replace('/', '-') + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + pkg.replace('.', '/') + "/" + type + ".java",
            "package " + pkg + ";\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"" + type + " is frozen\")\n"
                + "public class " + type + " {}\n");
        return harness;
    }

    private static List<String> warnings(ProcessorTestHarness harness) throws IOException {
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }
}
