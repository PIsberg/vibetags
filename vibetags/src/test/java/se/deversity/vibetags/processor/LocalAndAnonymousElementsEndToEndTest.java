package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guardrail annotations written inside method bodies: a local class, and a member of an anonymous
 * class. JSR 269 annotation processing sees declarations, not statements — neither element ever
 * reaches {@code getElementsAnnotatedWith}, so the annotation compiles and then does nothing at
 * all: no generated entry, no validation, no warning. The silent no-op is the defect; the
 * processor cannot process these elements, but whenever it runs it can see them through the Tree
 * API and say so.
 */
class LocalAndAnonymousElementsEndToEndTest {

    private static final String HOST_WITH_BODY_GUARDRAILS =
        "package com.example;\n"
            + "import se.deversity.vibetags.annotations.AIContext;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AIContext(focus = \"reachable declaration so the processor runs\", avoids = \"\")\n"
            + "public class Host {\n"
            + "    public Runnable make() {\n"
            + "        @AILocked(reason = \"local lock reason\")\n"
            + "        class LocalWork implements Runnable {\n"
            + "            @Override public void run() { }\n"
            + "        }\n"
            + "        new LocalWork().run();\n"
            + "        return new Runnable() {\n"
            + "            @AILocked(reason = \"anon lock reason\")\n"
            + "            @Override public void run() { }\n"
            + "        };\n"
            + "    }\n"
            + "}\n";

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void guardrailsInsideMethodBodies_areInvisible_andSaySo(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.Host", HOST_WITH_BODY_GUARDRAILS);
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "guardrails inside method bodies must never break the build: " + diagnostics);

        // The documented JSR 269 boundary: body-scoped annotations never reach the model, while
        // the reachable @AIContext on the class itself renders normally.
        String claude = h.readFile("CLAUDE.md");
        assertTrue(claude.contains("reachable declaration so the processor runs"),
            "the reachable guardrail must render:\n" + claude);
        assertFalse(claude.contains("local lock reason"),
            "annotation processing cannot see into method bodies — a local class's guardrail "
                + "cannot appear in the output:\n" + claude);
        assertFalse(claude.contains("anon lock reason"),
            "an anonymous class member's guardrail cannot appear in the output either:\n" + claude);

        // The fix for the silent no-op: the Tree API can see what the element model cannot, so
        // each body-scoped guardrail draws one WARNING naming the boundary and the way out.
        long bodyWarnings = diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .filter(d -> d.getMessage(null).contains("invisible to annotation processing"))
            .count();
        assertEquals(2, bodyWarnings,
            "each guardrail inside a method body must warn that it is a silent no-op: "
                + diagnostics);
        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .anyMatch(d -> d.getMessage(null).contains("no stable qualified name")),
            "the warning must also explain why hoisting is required: " + diagnostics);
    }

    /**
     * The residual boundary, pinned so nobody mistakes it for a bug later: when the <em>only</em>
     * VibeTags annotations in a compilation sit inside method bodies, javac's round universe
     * contains no VibeTags annotation at all, so the processor — which claims
     * {@code se.deversity.vibetags.annotations.*}, not {@code "*"} — is never invoked and nothing
     * can warn. Claiming {@code "*"} would run the processor on every compilation that contains
     * any annotation whatsoever, which is a price the SPI section of {@code docs/PROCESSOR.md}
     * deliberately declines to pay.
     */
    @Test
    void bodyOnlyGuardrails_cannotRunTheProcessorAtAll(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.BodyOnly",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class BodyOnly {\n"
                + "    public Runnable make() {\n"
                + "        return new Runnable() {\n"
                + "            @AILocked(reason = \"unreachable\")\n"
                + "            @Override public void run() { }\n"
                + "        };\n"
                + "    }\n"
                + "}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertEquals("", h.readFile("CLAUDE.md"),
            "the processor never ran, so the opted-in file stays untouched");
        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .noneMatch(d -> d.getMessage(null).contains("invisible to annotation processing")),
            "with no reachable VibeTags annotation, javac never invokes the processor and no "
                + "warning is possible — the boundary docs/PROCESSOR.md states: " + diagnostics);
    }

    /** A named member class is an ordinary element — the body scanner must stay quiet for it. */
    @Test
    void memberClassGuardrails_doNotTripTheBodyWarning(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.Outer",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Outer {\n"
                + "    @AILocked(reason = \"member lock reason\")\n"
                + "    public static class Inner { }\n"
                + "}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(h.readFile("CLAUDE.md").contains("member lock reason"),
            "a member class is processed normally");
        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .noneMatch(d -> d.getMessage(null).contains("invisible to annotation processing")),
            "a reachable declaration must not draw the body-scope warning: " + diagnostics);
    }
}
