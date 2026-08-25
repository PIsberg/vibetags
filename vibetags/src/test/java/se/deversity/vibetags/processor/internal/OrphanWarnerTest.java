package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "you annotated it but the file that enforces it is missing" diagnostic.
 *
 * <p>{@code @AIIgnore} and {@code @AILocked} are the two annotations whose effect depends on a file
 * VibeTags will not create: file presence is the only platform opt-in, so a project that never
 * created {@code .cursorignore} gets no {@code .cursorignore}, and the {@code @AIIgnore} on its
 * credentials class does nothing at Cursor. The annotation is right there in the source, which is
 * exactly why nobody looks for the missing file. This warning is the only thing that says so.
 *
 * <p>Both directions matter. A warning that does not fire leaves the false sense of protection in
 * place. A warning that fires when the file <em>is</em> there is noise on a correct project, and
 * gets muted along with the real ones.
 */
class OrphanWarnerTest {

    /** Collects diagnostics rather than mocking, so the assertions read against real text. */
    private static final class RecordingMessager implements Messager {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
            if (kind == Diagnostic.Kind.WARNING) {
                warnings.add(msg.toString());
            }
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e,
                                 AnnotationMirror a, AnnotationValue v) {
            printMessage(kind, msg);
        }

        boolean mentions(String fragment) {
            return warnings.stream().anyMatch(w -> w.contains(fragment));
        }
    }

    private static RecordingMessager warn(Set<String> active,
                                          boolean hasLocked, boolean hasIgnore, boolean hasAudit) {
        RecordingMessager messager = new RecordingMessager();
        OrphanWarner.warnAboutOrphans(messager, null, active, hasLocked, hasIgnore, hasAudit);
        return messager;
    }

    @Test
    void noAnnotationsMeansNoWarningsHoweverManyPlatformsAreActive() {
        RecordingMessager messager = warn(
            Set.of("cursor", "claude", "copilot", "qwen", "gemini", "codex"), false, false, false);
        assertEquals(List.of(), messager.warnings,
            "a project with neither @AIIgnore nor @AILocked has nothing to be missing a file for");
    }

    @Test
    void anAuditAnnotationAloneWarnsAboutNothing() {
        // hasAudit is carried through the signature but no ignore-file depends on it; a warning
        // here would fire on every project that uses @AIAudit.
        assertEquals(List.of(), warn(Set.of("cursor", "claude"), false, false, true).warnings);
    }

    @Test
    void eachPlatformIsWarnedAboutItsOwnMissingIgnoreFile() {
        RecordingMessager messager = warn(
            Set.of("cursor", "claude", "copilot", "qwen", "gemini"), false, true, false);

        assertTrue(messager.mentions(".cursorignore"), messager.warnings.toString());
        assertTrue(messager.mentions(".claudeignore"), messager.warnings.toString());
        assertTrue(messager.mentions(".copilotignore"), messager.warnings.toString());
        assertTrue(messager.mentions(".qwenignore"), messager.warnings.toString());
        assertTrue(messager.mentions(".aiexclude"), messager.warnings.toString());
    }

    @Test
    void aPlatformThatIsNotActiveIsNotWarnedAbout() {
        // Only Cursor is opted in, so the absence of .claudeignore is not this project's problem.
        RecordingMessager messager = warn(Set.of("cursor"), false, true, false);

        assertEquals(1, messager.warnings.size(), messager.warnings.toString());
        assertTrue(messager.mentions(".cursorignore"), messager.warnings.toString());
    }

    @Test
    void anIgnoreFileThatExistsSilencesItsOwnWarningOnly() {
        RecordingMessager messager = warn(
            Set.of("cursor", "cursor_ignore", "claude"), false, true, false);

        assertTrue(!messager.mentions(".cursorignore"),
            "the file is there; warning about it is noise: " + messager.warnings);
        assertTrue(messager.mentions(".claudeignore"),
            "...and must not silence the platform that really is missing one");
    }

    @Test
    void geminiAndCodexShareOneIgnoreFileAndOneWarning() {
        // Both read .aiexclude. Two warnings naming the same missing file would read as two
        // problems, and creating the file fixes both.
        assertEquals(1, warn(Set.of("gemini", "codex"), false, true, false).warnings.size());
        assertEquals(1, warn(Set.of("codex"), false, true, false).warnings.size());
        assertEquals(0, warn(Set.of("gemini", "codex", "aiexclude"), false, true, false).warnings.size());
    }

    @Test
    void lockedWithoutAiexcludeWarnsSeparatelyFromIgnore() {
        // @AILocked leans on .aiexclude as a hard guardrail, so the warning fires on @AILocked
        // alone, with no @AIIgnore anywhere in the project.
        RecordingMessager messager = warn(Set.of("gemini"), true, false, false);

        assertEquals(1, messager.warnings.size(), messager.warnings.toString());
        assertTrue(messager.mentions("@AILocked"), messager.warnings.toString());
        assertTrue(messager.mentions(".aiexclude"), messager.warnings.toString());
    }

    @Test
    void lockedAndIgnoreTogetherEachGetTheirOwnLine() {
        RecordingMessager messager = warn(Set.of("codex"), true, true, false);

        assertEquals(2, messager.warnings.size(),
            "the two annotations fail for different reasons and are fixed by the same file, but a "
                + "reader has to be able to see which annotation prompted which line: "
                + messager.warnings);
    }

    @Test
    void aPresentAiexcludeSilencesTheLockedWarningToo() {
        assertEquals(List.of(),
            warn(Set.of("gemini", "aiexclude"), true, false, false).warnings);
    }

    @Test
    void aNullLoggerIsAcceptedSoTheWarningStillReachesTheCompiler() {
        // The logger is optional scaffolding; the messager is the channel a user actually sees.
        RecordingMessager messager = new RecordingMessager();
        OrphanWarner.warnAboutOrphans(messager, null, Set.of("cursor"), false, true, false);
        assertEquals(1, messager.warnings.size());
    }
}
