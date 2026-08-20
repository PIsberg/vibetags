package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.deversity.vibetags.processor.internal.ModuleSidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two surviving regions claiming the same annotated element.
 *
 * <p>An element belongs to exactly one module, so two regions naming it are the same sources read
 * twice under two identities and every generated file states that guardrail twice. The region
 * prune retires the loser when one region's elements are <em>entirely</em> covered by a fresher
 * one, but it demands full containment on purpose: a reactor root that compiles sources of its own
 * keeps at least one element no submodule has, and retiring it would lose real guardrails.
 *
 * <p>That leaves a gap the reported case falls into. A leftover {@code .vibetags-mod-_root_} whose
 * element set is a superset only because it is stale fails containment by one element, so both
 * regions survive and everything they share is duplicated. It also explains why the report could
 * not be reproduced reliably: when the leftover's elements happen to match the live module's
 * exactly, containment holds and the prune cleans it up; one stale extra and it does not.
 *
 * <p>The build cannot tell a stale extra element from a genuinely root-owned one — a sidecar
 * records element ids, not whether their sources still exist — and a region carries rendered text
 * rather than a list, so it cannot have one element subtracted from it. So the duplication is
 * reported, with the sidecar to delete, rather than silently written. See issue #438.
 */
@Tag("e2e")
class DuplicateElementRegionWarningTest {

    private static final String NL = System.lineSeparator();
    private static final String Q = String.valueOf((char) 34);

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void twoRegionsClaimingOneElementAreReportedWithTheSidecarToDelete(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));

        // A leftover root sidecar from a build that resolved the ancestor directory, carrying one
        // element the live module no longer has. This is the reported shape.
        ModuleSidecar stale = new ModuleSidecar("_root_", "", "_root_");
        stale.putBody("claude", "stale root body");
        // Element ids are the granular stem form (dashes), which is what the collector records.
        stale.setElementIds(Set.of("com-example-svc-AuthUtil", "com-example-svc-Departed"));
        stale.save(root);
        Files.setLastModifiedTime(root.resolve(".vibetags-mod-_root_"), FileTime.fromMillis(1_000_000L));

        List<String> warnings = compileModuleCapturingWarnings(root, "svc",
            "com.example.svc.AuthUtil", locked("com.example.svc", "AuthUtil", "Token order"));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("com-example-svc-AuthUtil")
                && w.contains("_root_")),
            "the duplicated element and the sidecar to delete both have to be named. Silence is "
                + "the whole defect: the file is well-formed and states the guardrail twice. "
                + "Warnings were:" + NL + "  " + String.join(NL + "  ", warnings));
    }

    /** The guard: an ordinary reactor where every element belongs to one region says nothing. */
    @Test
    void staysSilentWhenEveryElementBelongsToExactlyOneRegion(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileModuleCapturingWarnings(root, "core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core node"));
        VibeTagsLogger.shutdown();

        List<String> warnings = compileModuleCapturingWarnings(root, "cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry"));

        assertTrue(warnings.stream().noneMatch(w -> w.contains("claim the same")),
            "a healthy reactor must never see this. Warnings were:" + NL + "  "
                + String.join(NL + "  ", warnings));
    }

    private static String locked(String pkg, String type, String reason) {
        return "package " + pkg + ";" + NL
            + "import se.deversity.vibetags.annotations.AILocked;" + NL
            + "@AILocked(reason = " + Q + reason + Q + ")" + NL
            + "public class " + type + " {}" + NL;
    }

    private static List<String> compileModuleCapturingWarnings(Path root, String module, String fqn,
                                                               String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace((char) 46, (char) 47) + ".java", source);
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.WARNING
                || d.getKind() == javax.tools.Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }
}
