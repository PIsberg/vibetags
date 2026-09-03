package se.deversity.vibetags.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two annotated elements whose rule filenames are identical keep both guardrails.
 *
 * <p>A granular stem replaces every character outside [A-Za-z0-9-] with a dash, dots included,
 * so the nested class {@code com.example.Foo.Bar} and the top-level class {@code com.example.Foo_Bar}
 * both plan {@code com-example-Foo-Bar}. The case fold of issue #510 groups stems that differ in
 * capitalisation, but a plan keyed by stem had already dropped the second of two equal stems
 * before the fold ran: the rule file held one element, the scoped-rules index pointed both at it,
 * and the other guardrail was in no output at all. Underscored class names are ordinary in
 * generated-adjacent code (JPA metamodels, protobuf), which is what makes this worth a test.
 */
@Tag("e2e")
class UnderscoreDotStemCollisionTest {

    @AfterEach
    void releaseLog() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void equalStemsFromDifferentElementsShareOneMergedRuleFile(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");
        h.addSource("com.example.Foo",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Foo {\n"
                + "    @AILocked(reason = \"nested wire format\")\n"
                + "    public static class Bar {}\n"
                + "}\n");
        h.addSource("com.example.Foo_Bar",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"top-level metamodel\")\n"
                + "public class Foo_Bar {}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();
        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "compile must succeed: " + diagnostics);

        Path rule = dir.resolve(".claude/rules/com-example-Foo-Bar.md");
        assertTrue(Files.isRegularFile(rule), "the shared rule file exists");
        String body = Files.readString(rule);
        assertTrue(body.contains("nested wire format"), "the nested class guardrail is in the file:\n" + body);
        assertTrue(body.contains("top-level metamodel"), "the top-level class guardrail is in the file:\n" + body);
        assertTrue(body.contains("com.example.Foo.Bar") && body.contains("com.example.Foo_Bar"),
            "each stanza names its fully-qualified element:\n" + body);
    }
}
