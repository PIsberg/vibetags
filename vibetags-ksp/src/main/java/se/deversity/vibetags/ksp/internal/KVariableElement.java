package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Set;

/** A field, enum constant or parameter in the stub model. */
final class KVariableElement extends KElement implements VariableElement {

    private final TypeMirror type;

    KVariableElement(ElementKind kind, String name, Set<Modifier> modifiers, TypeMirror type) {
        super(kind, name, modifiers);
        this.type = type;
    }

    @Override
    public TypeMirror asType() {
        return type;
    }

    @Override
    public @Nullable Object getConstantValue() {
        // Constant values never reach a generated file; the stub's initializer is not modelled.
        return null;
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
        return v.visitVariable(this, p);
    }
}
