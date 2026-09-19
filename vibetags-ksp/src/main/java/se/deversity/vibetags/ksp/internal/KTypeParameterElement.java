package se.deversity.vibetags.ksp.internal;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.Set;

/**
 * A type parameter of a class or method. Bounds are not modelled: a generic signature renders the
 * parameter by name ({@code <T>gen(T)}), and nothing downstream reads its bounds.
 */
final class KTypeParameterElement extends KElement implements TypeParameterElement {

    private final Element generic;

    KTypeParameterElement(String name, Element generic) {
        super(ElementKind.TYPE_PARAMETER, name, Set.of());
        this.generic = generic;
    }

    @Override
    public Element getGenericElement() {
        return generic;
    }

    @Override
    public Element getEnclosingElement() {
        return generic;
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
        return List.of();
    }

    @Override
    public TypeMirror asType() {
        return new KTypeMirror.Variable(this);
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
        return v.visitTypeParameter(this, p);
    }
}
