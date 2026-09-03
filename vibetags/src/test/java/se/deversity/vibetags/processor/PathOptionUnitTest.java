package se.deversity.vibetags.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

/**
 * {@link AIGuardrailProcessor#pathOption} on its own: the root case cannot go through the
 * harness, since a rejected root falls back to the working directory and generation would land
 * in the module being built.
 */
class PathOptionUnitTest {

    private static final String NUL = String.valueOf((char) 0);

    @Test
    void aRootTheFilesystemRejectsIsNoOverrideAndWarnsWithoutEchoingTheByte() {
        RecordingMessager messager = new RecordingMessager();
        Path resolved = AIGuardrailProcessor.pathOption(
            Map.of("vibetags.root", "bad" + NUL + "root"), "vibetags.root", messager);
        assertEquals(null, resolved, "an unusable value resolves to no override");
        assertEquals(1, messager.warnings.size(), messager.warnings.toString());
        assertTrue(messager.warnings.get(0).contains("vibetags.root"), messager.warnings.get(0));
        assertFalse(messager.warnings.get(0).contains(NUL),
            "the reason is reported, the byte a terminal cannot print is not: " + messager.warnings.get(0));
    }

    @Test
    void aBlankOrAbsentPathOptionIsNoOverrideAndNoWarning() {
        RecordingMessager messager = new RecordingMessager();
        assertEquals(null, AIGuardrailProcessor.pathOption(Map.of("vibetags.root", "  "), "vibetags.root", messager));
        assertEquals(null, AIGuardrailProcessor.pathOption(Map.of(), "vibetags.root", messager));
        assertTrue(messager.warnings.isEmpty());
    }

    @Test
    void aUsablePathOptionIsStrippedAndReturned() {
        RecordingMessager messager = new RecordingMessager();
        assertEquals(Path.of("sub", "dir"),
            AIGuardrailProcessor.pathOption(Map.of("vibetags.root", " sub/dir "), "vibetags.root", messager));
        assertTrue(messager.warnings.isEmpty());
    }

    /** The smallest Messager that remembers what it was told. */
    static final class RecordingMessager implements javax.annotation.processing.Messager {
        final List<String> warnings = new java.util.ArrayList<>();

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
            if (kind == Diagnostic.Kind.WARNING) {
                warnings.add(msg.toString());
            }
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e,
                                 javax.lang.model.element.AnnotationMirror a) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e,
                                 javax.lang.model.element.AnnotationMirror a,
                                 javax.lang.model.element.AnnotationValue v) {
            printMessage(kind, msg);
        }
    }
}
