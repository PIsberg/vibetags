package se.deversity.vibetags.processor.internal.validation;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.UnpairedSurrogates;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Warns about an attribute string holding a UTF-16 surrogate without its partner, which UTF-8
 * cannot encode (#1141).
 *
 * <p>The generated files carry such a character as {@code ?}, so the build goes on, but the text
 * the author wrote is not the text the agent reads. A mistyped unicode escape is the usual cause,
 * and only the source can fix it, so the warning names the element, the annotation, the attribute
 * and the character's position. Every explicitly written string is checked, inside arrays and
 * nested annotations too; defaults are VibeTags' own and well formed.
 */
final class UnpairedSurrogateRule implements ValidationRule {

    private final Class<? extends Annotation> scans;

    private UnpairedSurrogateRule(Class<? extends Annotation> scans) {
        this.scans = scans;
    }

    /** One rule per annotation in {@code annotations}, so each rides on that type's round query. */
    static List<ValidationRule> forEach(List<Class<? extends Annotation>> annotations) {
        return annotations.stream().<ValidationRule>map(UnpairedSurrogateRule::new).toList();
    }

    @Override
    public Class<? extends Annotation> scans() {
        return scans;
    }

    @Override
    public void check(ValidationContext ctx, Element element) {
        AnnotationMirror mirror = ValidationContext.mirrorOf(element, scans);
        if (mirror != null) {
            checkValues(ctx, element, mirror);
        }
    }

    private void checkValues(ValidationContext ctx, Element element, AnnotationMirror mirror) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : mirror.getElementValues().entrySet()) {
            checkValue(ctx, element, entry.getKey().getSimpleName().toString(), entry.getValue().getValue());
        }
    }

    private void checkValue(ValidationContext ctx, Element element, String attribute, @Nullable Object value) {
        if (value instanceof String s) {
            int at = UnpairedSurrogates.firstIn(s);
            if (at >= 0) {
                ctx.warn(element, scans, "@" + scans.getSimpleName() + " on " + element + ": attribute '"
                    + attribute + "' holds an unpaired UTF-16 surrogate (U+"
                    + Integer.toHexString(s.charAt(at)).toUpperCase(Locale.ROOT) + ") at index " + at
                    + ", which UTF-8 cannot encode. The generated files carry it as '?'; fix the escape in the source.");
            }
        } else if (value instanceof List<?> values) {
            for (Object item : values) {
                if (item instanceof AnnotationValue av) {
                    checkValue(ctx, element, attribute, av.getValue());
                }
            }
        } else if (value instanceof AnnotationMirror nested) {
            checkValues(ctx, element, nested);
        }
    }
}
