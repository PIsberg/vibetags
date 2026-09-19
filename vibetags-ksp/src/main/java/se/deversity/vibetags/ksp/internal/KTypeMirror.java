package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * The type mirrors a kapt stub's signatures are made of. {@code ElementNaming} renders a member
 * signature from their structure, never from {@link #toString()}, so the structure is what has to
 * be right; {@code toString()} follows javac's format for the diagnostics that print a type.
 *
 * <p>Type annotations are not modelled: no {@code @AI*} annotation targets a type use.
 */
abstract class KTypeMirror implements TypeMirror {

    private final TypeKind kind;

    KTypeMirror(TypeKind kind) {
        this.kind = kind;
    }

    @Override
    public TypeKind getKind() {
        return kind;
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
        return List.of();
    }

    @Override
    public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationType) {
        A[] present = getAnnotationsByType(annotationType);
        return present.length == 0 ? null : present[0];
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
        return (A[]) Array.newInstance(annotationType, 0);
    }

    /** A class, interface or other declared type, with its (possibly wildcarded) arguments. */
    static final class Declared extends KTypeMirror implements DeclaredType {
        private final TypeElement element;
        private final List<TypeMirror> arguments;

        Declared(TypeElement element, List<TypeMirror> arguments) {
            super(TypeKind.DECLARED);
            this.element = element;
            this.arguments = List.copyOf(arguments);
        }

        @Override
        public Element asElement() {
            return element;
        }

        @Override
        public TypeMirror getEnclosingType() {
            return NoneType.NONE;
        }

        @Override
        public List<? extends TypeMirror> getTypeArguments() {
            return arguments;
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitDeclared(this, p);
        }

        @Override
        public String toString() {
            String name = element.getQualifiedName().toString();
            if (arguments.isEmpty()) {
                return name;
            }
            StringJoiner joined = new StringJoiner(",", name + "<", ">");
            for (TypeMirror argument : arguments) {
                joined.add(argument.toString());
            }
            return joined.toString();
        }
    }

    /** {@code T[]}; a Kotlin {@code vararg} is one of these in the last parameter position. */
    static final class Arr extends KTypeMirror implements ArrayType {
        private final TypeMirror component;

        Arr(TypeMirror component) {
            super(TypeKind.ARRAY);
            this.component = component;
        }

        @Override
        public TypeMirror getComponentType() {
            return component;
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitArray(this, p);
        }

        @Override
        public String toString() {
            return component + "[]";
        }
    }

    /** {@code int}, {@code boolean} and the other six. */
    static final class Primitive extends KTypeMirror implements PrimitiveType {
        Primitive(TypeKind kind) {
            super(kind);
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitPrimitive(this, p);
        }

        @Override
        public String toString() {
            return getKind().name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code void} as a return type, and "no type" where javac has none (an interface's superclass). */
    static final class NoneType extends KTypeMirror implements NoType {
        static final NoneType VOID = new NoneType(TypeKind.VOID);
        static final NoneType NONE = new NoneType(TypeKind.NONE);

        private NoneType(TypeKind kind) {
            super(kind);
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitNoType(this, p);
        }

        @Override
        public String toString() {
            return getKind() == TypeKind.VOID ? "void" : "none";
        }
    }

    /** {@code ?}, {@code ? extends T} or {@code ? super T}. */
    static final class Wildcard extends KTypeMirror implements WildcardType {
        private final @Nullable TypeMirror extendsBound;
        private final @Nullable TypeMirror superBound;

        Wildcard(@Nullable TypeMirror extendsBound, @Nullable TypeMirror superBound) {
            super(TypeKind.WILDCARD);
            this.extendsBound = extendsBound;
            this.superBound = superBound;
        }

        @Override
        public @Nullable TypeMirror getExtendsBound() {
            return extendsBound;
        }

        @Override
        public @Nullable TypeMirror getSuperBound() {
            return superBound;
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitWildcard(this, p);
        }

        @Override
        public String toString() {
            if (extendsBound != null) {
                return "? extends " + extendsBound;
            }
            if (superBound != null) {
                return "? super " + superBound;
            }
            return "?";
        }
    }

    /** A use of a type parameter; renders as its name, the way javac's does. */
    static final class Variable extends KTypeMirror implements TypeVariable {
        private final Element element;

        Variable(Element element) {
            super(TypeKind.TYPEVAR);
            this.element = element;
        }

        @Override
        public Element asElement() {
            return element;
        }

        @Override
        public TypeMirror getUpperBound() {
            return NoneType.NONE;
        }

        @Override
        public TypeMirror getLowerBound() {
            return NoneType.NONE;
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitTypeVariable(this, p);
        }

        @Override
        public String toString() {
            return element.getSimpleName().toString();
        }
    }
}
