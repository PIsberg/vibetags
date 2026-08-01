package se.deversity.vibetags.processor.internal.validation;

import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;

/**
 * One compile-time consistency check, bound to the annotation whose elements it inspects.
 *
 * <p>A rule reads and reports through {@link ValidationContext} and never touches the
 * {@code Messager} or the {@code RoundEnvironment} itself. That is what makes a rule testable on
 * its own: hand it a context and an element, assert on what it reported.
 *
 * <p>Splitting {@link #scans()} out from {@link #check} is not decoration. It lets
 * {@link ValidationRules} group every rule that inspects the same annotation and run
 * {@code getElementsAnnotatedWith} <em>once</em> for the group — that query walks the round's root
 * elements, and the validator used to issue one per check, four of them for {@code @AITestDriven}
 * alone.
 *
 * <p>Rules must be stateless. The registry holds one instance of each for the life of the JVM, and
 * a Gradle daemon runs them against many unrelated compilations.
 */
public interface ValidationRule {

    /** The annotation whose annotated elements this rule is called for. */
    Class<? extends Annotation> scans();

    /**
     * Checks one element already known to carry {@link #scans()}, reporting through {@code ctx}.
     * Called once per matching element per round.
     */
    void check(ValidationContext ctx, Element element);
}
