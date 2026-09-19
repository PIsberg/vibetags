package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.SourceLocation;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Common state of every element in a stub model: kind, name, modifiers, the annotations kapt
 * would have placed on it, and its place in the tree.
 *
 * <p>Identity is object identity, as it is for javac's elements: the model builds exactly one
 * instance per stub element, and the processor keys sets on them.
 */
abstract class KElement implements Element {

    private final ElementKind kind;
    private final KName simpleName;
    private final Set<Modifier> modifiers;
    private final List<AnnotationData> annotations = new ArrayList<>();
    private final List<KElement> enclosed = new ArrayList<>();
    private @Nullable KElement enclosing;
    private @Nullable SourceLocation location;

    KElement(ElementKind kind, String simpleName, Set<Modifier> modifiers) {
        this.kind = kind;
        this.simpleName = new KName(simpleName);
        this.modifiers = modifiers.isEmpty() ? EnumSet.noneOf(Modifier.class) : EnumSet.copyOf(modifiers);
    }

    /** Adds {@code child} as an enclosed element and makes this its enclosing element. */
    final <E extends KElement> E adopt(E child) {
        KElement node = child;
        node.enclosing = this;
        enclosed.add(node);
        return child;
    }

    /** Records an annotation, ignoring a second copy of the same type (one declaration, one use). */
    final void annotate(AnnotationData annotation) {
        for (AnnotationData existing : annotations) {
            if (existing.type().equals(annotation.type())) {
                return;
            }
        }
        annotations.add(annotation);
    }

    final List<AnnotationData> annotations() {
        return annotations;
    }

    final boolean hasAnnotation(String type) {
        for (AnnotationData annotation : annotations) {
            if (annotation.type().equals(type)) {
                return true;
            }
        }
        return false;
    }

    final List<KElement> enclosedElements() {
        return enclosed;
    }

    /** Where the Kotlin declaration this element stands for is written, when known. */
    final @Nullable SourceLocation location() {
        return location;
    }

    final void locate(@Nullable SourceLocation where) {
        this.location = where;
    }

    @Override
    public ElementKind getKind() {
        return kind;
    }

    @Override
    public Set<Modifier> getModifiers() {
        return modifiers.isEmpty() ? Set.of() : EnumSet.copyOf(modifiers);
    }

    @Override
    public Name getSimpleName() {
        return simpleName;
    }

    @Override
    public @Nullable Element getEnclosingElement() {
        return enclosing;
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
        return List.copyOf(enclosed);
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
        // No mirrors: validation falls back to anchoring a warning at the element, which is what
        // a KSP diagnostic can point at anyway.
        return List.of();
    }

    @Override
    public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationType) {
        String name = annotationType.getName();
        for (AnnotationData annotation : annotations) {
            if (annotation.type().equals(name)) {
                return AnnotationProxies.create(annotationType, annotation);
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
        A found = getAnnotation(annotationType);
        A[] result = (A[]) Array.newInstance(annotationType, found == null ? 0 : 1);
        if (found != null) {
            result[0] = found;
        }
        return result;
    }

    @Override
    public abstract TypeMirror asType();

    @Override
    public String toString() {
        return simpleName.toString();
    }
}
