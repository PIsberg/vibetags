package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two annotated elements whose rule filenames differ only in capitalisation keep both guardrails
 * on every filesystem (issue #510).
 *
 * <p>A granular stem is the element's fully-qualified name with its dots turned into dashes, so the
 * package {@code com.example.payment} and the class {@code com.example.Payment} plan
 * {@code com-example-payment} and {@code com-example-Payment}. A case-sensitive filesystem holds
 * two files; a case-insensitive one, which is what Windows and macOS give you by default, holds
 * one. Measured on Windows before the fold existed, the rules directory held a single
 * {@code com-example-payment.md} and the class's {@code @AILocked} appeared nowhere in the output
 * tree, while the scoped-rules index went on naming both.
 *
 * <p>The fix plans one merged, byte-identical rule file under <em>each</em> colliding name, so the
 * content assertion here holds on all three CI runners without knowing the filesystem: however
 * many files survive, every one of them carries every colliding element's guardrails. The
 * remaining cross-platform difference — the file count — is said out loud as a NOTE, which the
 * second test pins in the other direction: ordinary builds must not hear about it.
 */
@Tag("e2e")
class CaseCollidingGranularStemTest {

    @AfterEach
    void releaseLog() {
        VibeTagsLogger.shutdown();
    }

    @Test
    @DisplayName("every rule file a case collision leaves behind carries both elements' guardrails")
    void collidingStemsShareOneMergedBodyUnderEachName(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");

        h.addSource("com.example.payment.package-info",
            "@se.deversity.vibetags.annotations.AIContext(focus = \"settlement timing\")\n"
                + "package com.example.payment;\n");
        h.addSource("com.example.Payment",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"wire format\")\n"
                + "public class Payment {}\n");

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        // The acceptance from #510, phrased so it holds on every runner without knowing the
        // filesystem: however many of the colliding files this filesystem keeps, each one holds
        // every colliding element's guardrails, so both index entries reach both rules.
        List<Path> collidingFiles;
        try (var entries = java.nio.file.Files.list(dir.resolve(".claude/rules"))) {
            collidingFiles = entries
                .filter(p -> String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT)
                    .equals("com-example-payment.md"))
                .toList();
        }
        assertTrue(!collidingFiles.isEmpty(),
            "no rule file written for the colliding stems at all");
        for (Path file : collidingFiles) {
            String content = java.nio.file.Files.readString(file);
            assertTrue(content.contains("settlement timing") && content.contains("wire format"),
                file.getFileName() + " must carry both colliding elements' guardrails, whichever "
                    + "of the case-colliding names this filesystem kept. Content: " + content);
            assertTrue(content.contains("com.example.payment") && content.contains("com.example.Payment"),
                file.getFileName() + " covers two elements, so its stanza headings must say which "
                    + "fully-qualified element each rule binds. Content: " + content);
        }

        assertTrue(noted(diagnostics, "com-example-payment", "com-example-Payment"),
            "the cross-platform file-count difference the merge cannot remove must still be said "
                + "out loud. Diagnostics: " + messages(diagnostics));
    }

    @Test
    @DisplayName("ordinary distinct rule files raise no such warning")
    void distinctStemsAreNotWarnedAbout(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");

        h.addSource("com.example.Payment",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"wire format\")\n"
                + "public class Payment {}\n");
        h.addSource("com.example.Refund",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"ledger contract\")\n"
                + "public class Refund {}\n");

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream()
                .map(d -> d.getMessage(Locale.ROOT))
                .noneMatch(m -> m.contains("differ only in capitalisation")),
            "a warning on every ordinary build is one nobody reads. Diagnostics: "
                + messages(diagnostics));
    }

    private static boolean noted(List<Diagnostic<? extends JavaFileObject>> diagnostics,
                                 String first, String second) {
        // A NOTE and not a warning: the merge already handled the collision, and a warning on
        // handled behaviour is how a team ends up muting the processor.
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.NOTE || d.getKind() == Diagnostic.Kind.OTHER)
            .map(d -> d.getMessage(Locale.ROOT))
            .anyMatch(m -> m.contains("differ only in capitalisation")
                && m.contains(first) && m.contains(second));
    }

    private static String messages(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream().map(d -> d.getMessage(Locale.ROOT)).toList().toString();
    }
}
