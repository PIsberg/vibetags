package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * {@link Types} over the stub model. The processor's one real question is the locked-override
 * check's {@code isSubtype(erasure(a), erasure(b))}, answered from the supertypes recorded while
 * the KSP round was live. Everything else answers conservatively: see {@link KspElements} for why
 * nothing here throws.
 */
final class KspTypes implements Types {

    @Override
    public @Nullable Element asElement(TypeMirror t) {
        if (t instanceof DeclaredType declared) {
            return declared.asElement();
        }
        if (t instanceof javax.lang.model.type.TypeVariable variable) {
            return variable.asElement();
        }
        return null;
    }

    @Override
    public boolean isSameType(TypeMirror t1, TypeMirror t2) {
        return t1.toString().equals(t2.toString());
    }

    @Override
    public boolean isSubtype(TypeMirror t1, TypeMirror t2) {
        if (isSameType(t1, t2)) {
            return true;
        }
        if (t1 instanceof DeclaredType sub && t2 instanceof DeclaredType sup
                && sub.asElement() instanceof KTypeElement subType
                && sup.asElement() instanceof TypeElement superType) {
            return subType.isSubtypeOf(superType.getQualifiedName().toString());
        }
        return false;
    }

    @Override
    public boolean isAssignable(TypeMirror t1, TypeMirror t2) {
        return isSubtype(t1, t2);
    }

    @Override
    public boolean contains(TypeMirror t1, TypeMirror t2) {
        return isSameType(t1, t2);
    }

    @Override
    public boolean isSubsignature(ExecutableType m1, ExecutableType m2) {
        return m1.getParameterTypes().toString().equals(m2.getParameterTypes().toString());
    }

    @Override
    public List<? extends TypeMirror> directSupertypes(TypeMirror t) {
        return List.of();
    }

    @Override
    public TypeMirror erasure(TypeMirror t) {
        if (t instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            return new KTypeMirror.Declared(element, List.of());
        }
        return t;
    }

    @Override
    public TypeElement boxedClass(PrimitiveType p) {
        return KTypeElement.reference(switch (p.getKind()) {
            case INT -> "java.lang.Integer";
            case CHAR -> "java.lang.Character";
            default -> "java.lang." + Character.toUpperCase(p.toString().charAt(0)) + p.toString().substring(1);
        });
    }

    @Override
    public PrimitiveType unboxedType(TypeMirror t) {
        throw new IllegalArgumentException("not a boxed type: " + t);
    }

    @Override
    public TypeMirror capture(TypeMirror t) {
        return t;
    }

    @Override
    public PrimitiveType getPrimitiveType(TypeKind kind) {
        return new KTypeMirror.Primitive(kind);
    }

    @Override
    public NullType getNullType() {
        throw new UnsupportedOperationException("the null type is not modelled");
    }

    @Override
    public NoType getNoType(TypeKind kind) {
        return kind == TypeKind.VOID ? KTypeMirror.NoneType.VOID : KTypeMirror.NoneType.NONE;
    }

    @Override
    public ArrayType getArrayType(TypeMirror componentType) {
        return new KTypeMirror.Arr(componentType);
    }

    @Override
    public WildcardType getWildcardType(@Nullable TypeMirror extendsBound, @Nullable TypeMirror superBound) {
        return new KTypeMirror.Wildcard(extendsBound, superBound);
    }

    @Override
    public DeclaredType getDeclaredType(TypeElement typeElem, TypeMirror... typeArgs) {
        return new KTypeMirror.Declared(typeElem, List.of(typeArgs));
    }

    @Override
    public DeclaredType getDeclaredType(DeclaredType containing, TypeElement typeElem, TypeMirror... typeArgs) {
        return getDeclaredType(typeElem, typeArgs);
    }

    @Override
    public TypeMirror asMemberOf(DeclaredType containing, Element element) {
        return element.asType();
    }
}
