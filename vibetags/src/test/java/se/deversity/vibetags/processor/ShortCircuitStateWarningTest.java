package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.deversity.vibetags.processor.internal.ModuleSidecar;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A warning about state that is still on disk must be printed by the rebuild that changes nothing,
 * not only by the build that regenerated (#1063).
 *
 * <p>{@code generateFiles()} raises these after its fingerprint short-circuit, and the source-digest
 * early exit skips {@code generateFiles()} altogether, so a second build with nothing changed
 * printed none of them. Where the condition persists, a {@code -Werror} build then failed on the
 * build that regenerated and passed on the rebuild: the shape #859 fixed for the warnings raised
 * before the short-circuit. Each case builds twice and asserts the second build warns as well.
 *
 * <p>The one warning left out describes the write just made rather than a state: a module whose
 * every element was replaced ({@code regionReplaced}) compares against the sidecar that write
 * overwrote, so there is nothing for a rebuild to repeat.
 */
@Tag("e2e")
class ShortCircuitStateWarningTest {

    private static final String NL = System.lineSeparator();
    private static final String Q = String.valueOf((char) 34);

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aDetachedModuleIsWarnedAboutOnTheRebuild(@TempDir Path tmp) throws Exception {
        Path reactor = tmp.resolve("reactor");
        Files.createDirectories(reactor);
        Files.writeString(reactor.resolve("pom.xml"),
            "<project><modules><module>module-core</module></modules></project>", StandardCharsets.UTF_8);
        Path module = reactor.resolve("module-core");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"),
            "<project><artifactId>module-core</artifactId></project>", StandardCharsets.UTF_8);
        Files.createFile(module.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(module, false);
        harness.writeSourceFile("src/main/java/com/example/core/IrNode.java",
            locked("com.example.core", "IrNode", "core model"));

        assertWarnedOnBothBuilds(harness, "generated its guardrails as its own root");
    }

    @Test
    void anElementClaimedByTwoRegionsIsWarnedAboutOnTheRebuild(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar stale = new ModuleSidecar("_root_", "", "_root_");
        stale.putBody("claude", "stale root body");
        stale.setElementIds(Set.of("com-example-svc-AuthUtil", "com-example-svc-Departed"));
        stale.save(root);
        Files.setLastModifiedTime(root.resolve(".vibetags-mod-_root_"), FileTime.fromMillis(1_000_000L));

        assertWarnedOnBothBuilds(module(root, "svc", "com.example.svc.AuthUtil",
            locked("com.example.svc", "AuthUtil", "Token order")), "claim the same annotated element");
    }

    @Test
    void aSiblingsMissingRuleFileIsWarnedAboutOnTheRebuild(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness alpha = module(root, "module-a", "com.example.a.Alpha", context("com.example.a", "Alpha"));
        compile(alpha);
        compile(module(root, "module-b", "com.example.b.Beta", context("com.example.b", "Beta")));
        Path betaRule = root.resolve(".claude/rules/com-example-b-Beta.md");
        assertTrue(Files.exists(betaRule), "precondition: module-b wrote its rule file");
        Files.delete(betaRule);
        ProcessorTestHarness.awaitFilesystemTick(root);

        assertWarnedOnBothBuilds(alpha, "com-example-b-Beta");
    }

    @Test
    void aGranularOptOutASiblingHasNotSeenIsWarnedAboutOnTheRebuild(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness alpha = module(root, "module-a", "com.example.a.Alpha", context("com.example.a", "Alpha"));
        compile(alpha);
        compile(module(root, "module-b", "com.example.b.Beta", context("com.example.b", "Beta")));
        try (Stream<Path> walk = Files.walk(root.resolve(".claude"))) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        ProcessorTestHarness.awaitFilesystemTick(root);

        assertWarnedOnBothBuilds(alpha, "granular rules directory was opted out");
    }

    @Test
    void anOptInASiblingHasNotCompiledSinceIsWarnedAboutOnTheRebuild(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        ProcessorTestHarness alpha = module(root, "module-a", "com.example.a.Alpha", locked("com.example.a", "Alpha", "alpha"));
        compile(alpha);
        compile(module(root, "module-b", "com.example.b.Beta", locked("com.example.b", "Beta", "beta")));
        ProcessorTestHarness.awaitFilesystemTick(root);
        Files.createFile(root.resolve("llms.txt"));

        assertWarnedOnBothBuilds(alpha, "was opted into after");
    }

    @Test
    void anUnreadableSidecarIsWarnedAboutOnTheRebuild(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("ghost"));
        ModuleSidecar ghost = new ModuleSidecar("ghost", "ghost", "ghost");
        ghost.putBody("claude", "ghost body");
        ghost.setElementIds(Set.of("com-example-ghost-Ghost"));
        ghost.save(root);
        Path file = root.resolve(".vibetags-mod-ghost");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        // Without its trailer the file reads as one a parallel build is still writing.
        Files.write(file, lines.stream().filter(l -> !l.strip().equals("# end")).toList(), StandardCharsets.UTF_8);

        assertWarnedOnBothBuilds(module(root, "svc", "com.example.svc.AuthUtil",
            locked("com.example.svc", "AuthUtil", "Token order")), "could not be read this round");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertWarnedOnBothBuilds(ProcessorTestHarness harness, String fragment) throws IOException {
        List<String> first = warnings(harness);
        assertTrue(first.stream().anyMatch(w -> w.contains(fragment)),
            "precondition: the first build warns about " + Q + fragment + Q + ". Warnings were:" + NL + "  "
                + String.join(NL + "  ", first));

        List<String> second = warnings(harness);

        assertTrue(second.stream().anyMatch(w -> w.contains(fragment)),
            "nothing changed and the condition is still on disk, so the rebuild must warn as the first"
                + " build did. Second build's warnings:" + NL + "  " + String.join(NL + "  ", second));
        assertEquals(1, first.stream().filter(w -> w.contains(fragment)).count(),
            "the regenerating build must warn once, not again from the short-circuit path: " + first);
        assertEquals(1, second.stream().filter(w -> w.contains(fragment)).count(),
            "the rebuild must warn once: " + second);
    }

    private static void compile(ProcessorTestHarness harness) throws IOException {
        warnings(harness);
    }

    private static List<String> warnings(ProcessorTestHarness harness) throws IOException {
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

    private static ProcessorTestHarness module(Path root, String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        return harness;
    }

    private static String locked(String pkg, String type, String reason) {
        return "package " + pkg + ";" + NL
            + "import se.deversity.vibetags.annotations.AILocked;" + NL
            + "@AILocked(reason = " + Q + reason + Q + ")" + NL
            + "public class " + type + " {}" + NL;
    }

    private static String context(String pkg, String type) {
        return "package " + pkg + ";" + NL
            + "import se.deversity.vibetags.annotations.AIContext;" + NL
            + "@AIContext(focus = " + Q + type + "-routing" + Q + ")" + NL
            + "public class " + type + " {}" + NL;
    }
}
