package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.processing.KSPLogger;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Routes the processor's diagnostics to KSP's logger. An {@code ERROR} fails the KSP task exactly
 * as it fails javac, which is what check mode and the enforcing mode rely on.
 *
 * <p>A {@code NOTE} goes to {@link KSPLogger#info}, which Gradle shows only at {@code --info}:
 * javac prints notes by default, but KSP's convention is that a processor's chatter stays out of
 * a normal build log. Warnings and errors are unaffected.
 */
final class KspMessager implements Messager {

    private final KSPLogger logger;

    KspMessager(KSPLogger logger) {
        this.logger = logger;
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        String text = msg.toString();
        switch (kind) {
            case ERROR -> logger.error(text, null);
            case WARNING, MANDATORY_WARNING -> logger.warn(text, null);
            case NOTE -> logger.info(text, null);
            default -> logger.logging(text, null);
        }
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, @Nullable Element e) {
        printMessage(kind, e == null ? msg : msg + " [" + e + "]");
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, @Nullable Element e,
                             @Nullable AnnotationMirror a) {
        printMessage(kind, msg, e);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, @Nullable Element e,
                             @Nullable AnnotationMirror a, @Nullable AnnotationValue v) {
        printMessage(kind, msg, e);
    }
}
