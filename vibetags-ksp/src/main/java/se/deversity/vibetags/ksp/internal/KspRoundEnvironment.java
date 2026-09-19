package se.deversity.vibetags.ksp.internal;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One JSR 269 round over a stub model. The final round, {@code processingOver()}, has no root
 * elements, exactly as javac's does.
 */
final class KspRoundEnvironment implements RoundEnvironment {

    private final List<KTypeElement> roots;
    private final List<KElement> all;
    private final boolean over;
    private final boolean errorRaised;

    private KspRoundEnvironment(List<KTypeElement> roots, List<KElement> all, boolean over, boolean errorRaised) {
        this.roots = roots;
        this.all = all;
        this.over = over;
        this.errorRaised = errorRaised;
    }

    /** A processing round over {@code model}. */
    static KspRoundEnvironment of(StubModel model) {
        return new KspRoundEnvironment(model.roots(), model.all(), false, false);
    }

    /** The final round, after every processing round. */
    static KspRoundEnvironment over(boolean errorRaised) {
        return new KspRoundEnvironment(List.of(), List.of(), true, errorRaised);
    }

    /** The qualified names of every annotation present in this round, as javac reports them. */
    Set<String> presentAnnotationTypes() {
        Set<String> present = new LinkedHashSet<>();
        for (KElement element : all) {
            for (AnnotationData annotation : element.annotations()) {
                present.add(annotation.type());
            }
        }
        return present;
    }

    @Override
    public boolean processingOver() {
        return over;
    }

    @Override
    public boolean errorRaised() {
        return errorRaised;
    }

    @Override
    public Set<? extends Element> getRootElements() {
        return new LinkedHashSet<>(roots);
    }

    @Override
    public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
        return annotatedWith(a.getQualifiedName().toString());
    }

    @Override
    public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {
        return annotatedWith(a.getName());
    }

    private Set<Element> annotatedWith(String type) {
        Set<Element> found = new LinkedHashSet<>();
        for (KElement element : all) {
            if (element.hasAnnotation(type)) {
                found.add(element);
            }
        }
        return found;
    }
}
