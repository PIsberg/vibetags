package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Elements} over the stub model, answering what the processor asks: which file an element
 * came from (module identity and the partial-round ledger both need it), which package it is in,
 * and whether one method overrides another. The rest answers conservatively rather than throwing,
 * because an exception anywhere in the processor skips guardrail generation for the whole build.
 */
final class KspElements implements Elements {

    private final StubModel model;
    /** The previous rounds' elements, consulted for what this round's model does not declare. */
    private final @Nullable KspElements earlier;

    /**
     * Elements over {@code model}, falling back to {@code earlier} for a type or package only an
     * earlier round declared. javac's {@code Elements} answers for every round's types; a later KSP
     * round's model holds only that round's new files, and replacing the first round's elements with
     * it left the final round unable to find any type from round 1 (#1122).
     */
    KspElements(StubModel model, @Nullable KspElements earlier) {
        this.model = model;
        this.earlier = earlier;
    }

    /** The Kotlin source file declaring {@code e}, as the processor's source-file lookups expect. */
    @Override
    public @Nullable JavaFileObject getFileObjectOf(Element e) {
        Path source = StubModel.sourceOf(e);
        if (source == null) {
            return null;
        }
        return new SimpleJavaFileObject(source.toUri(), JavaFileObject.Kind.OTHER) { };
    }

    @Override
    public @Nullable PackageElement getPackageOf(Element e) {
        for (Element current = e; current != null; current = current.getEnclosingElement()) {
            if (current instanceof PackageElement pkg) {
                return pkg;
            }
        }
        return null;
    }

    @Override
    public boolean overrides(ExecutableElement overrider, ExecutableElement overridden, TypeElement type) {
        return overrider instanceof KExecutableElement candidate
            && overridden instanceof KExecutableElement target
            && candidate.overrides(target);
    }

    @Override
    public @Nullable TypeElement getTypeElement(CharSequence name) {
        TypeElement type = model.type(name.toString());
        return type != null || earlier == null ? type : earlier.getTypeElement(name);
    }

    @Override
    public @Nullable PackageElement getPackageElement(CharSequence name) {
        for (KTypeElement root : model.roots()) {
            PackageElement pkg = getPackageOf(root);
            if (pkg != null && pkg.getQualifiedName().contentEquals(name)) {
                return pkg;
            }
        }
        return earlier == null ? null : earlier.getPackageElement(name);
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(
            AnnotationMirror a) {
        return Map.of(); // the model hands out no mirrors
    }

    @Override
    public @Nullable String getDocComment(Element e) {
        return null;
    }

    @Override
    public boolean isDeprecated(Element e) {
        return e.getAnnotation(Deprecated.class) != null;
    }

    @Override
    public Name getBinaryName(TypeElement type) {
        return type.getQualifiedName();
    }

    @Override
    public List<? extends Element> getAllMembers(TypeElement type) {
        return new ArrayList<>(type.getEnclosedElements());
    }

    @Override
    public List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e) {
        return List.of();
    }

    @Override
    public boolean hides(Element hider, Element hidden) {
        return false;
    }

    @Override
    public String getConstantExpression(Object value) {
        return String.valueOf(value);
    }

    @Override
    public void printElements(Writer w, Element... elements) {
        PrintWriter out = new PrintWriter(w);
        for (Element element : elements) {
            out.println(element);
        }
        out.flush();
    }

    @Override
    public Name getName(CharSequence cs) {
        return new KName(cs.toString());
    }

    @Override
    public boolean isFunctionalInterface(TypeElement type) {
        return false;
    }
}
