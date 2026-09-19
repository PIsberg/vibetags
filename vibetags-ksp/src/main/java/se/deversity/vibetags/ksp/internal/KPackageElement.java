package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.type.TypeMirror;
import java.util.Set;

/**
 * A package in the stub model. Kotlin has no {@code package-info}, so a package never carries a
 * guardrail of its own here; it exists so that a top-level type has an enclosing element, as it
 * does under javac.
 */
final class KPackageElement extends KElement implements PackageElement {

    private final KName qualifiedName;

    KPackageElement(String qualifiedName) {
        super(ElementKind.PACKAGE, simpleNameOf(qualifiedName), Set.of());
        this.qualifiedName = new KName(qualifiedName);
    }

    private static String simpleNameOf(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    @Override
    public Name getQualifiedName() {
        return qualifiedName;
    }

    @Override
    public boolean isUnnamed() {
        return qualifiedName.length() == 0;
    }

    @Override
    public @Nullable Element getEnclosingElement() {
        return null; // the module, which KSP has no model of
    }

    @Override
    public TypeMirror asType() {
        return KTypeMirror.NoneType.NONE;
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
        return v.visitPackage(this, p);
    }

    @Override
    public String toString() {
        return qualifiedName.toString();
    }
}
