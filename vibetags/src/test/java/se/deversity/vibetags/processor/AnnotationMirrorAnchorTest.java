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

/**
 * Where a pair-rule diagnostic lands in the editor. Anchoring at the element puts the caret on the
 * declaration; anchoring at the offending annotation's own {@code AnnotationMirror} puts it on the
 * annotation — which is the line the fix touches, since resolving a contradiction means removing
 * one of the two annotations, not editing the declaration.
 */
class AnnotationMirrorAnchorTest {

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void pairRuleWarning_pointsAtTheConflictingAnnotationLine(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.addSource("com.example.Both",
            "package com.example;\n"                                  // line 1
                + "import se.deversity.vibetags.annotations.AIDraft;\n"   // line 2
                + "import se.deversity.vibetags.annotations.AILocked;\n"  // line 3
                + "@AIDraft(instructions = \"finish me\")\n"              // line 4
                + "@AILocked(reason = \"frozen\")\n"                      // line 5
                + "public class Both {\n"                                 // line 6
                + "}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        Diagnostic<? extends JavaFileObject> pairWarning = diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .filter(d -> d.getMessage(null).contains("@AIDraft and @AILocked"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "expected the @AIDraft/@AILocked pair warning, got: " + diagnostics));

        assertEquals(4, pairWarning.getLineNumber(),
            "the warning must point at the conflicting @AIDraft annotation (line 4), "
                + "not at the class declaration");
    }
}
