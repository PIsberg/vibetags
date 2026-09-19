package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A method or constructor as kapt's stub declares it: JVM name, JVM parameter list (receiver first,
 * continuation last), and the annotations kapt copied onto it.
 */
final class KExecutableElement extends KElement implements ExecutableElement {

    private final List<KVariableElement> parameters = new ArrayList<>();
    private final List<KTypeParameterElement> typeParameters = new ArrayList<>();
    private final List<KExecutableElement> overridden = new ArrayList<>();
    private TypeMirror returnType = KTypeMirror.NoneType.VOID;
    private final boolean varArgs;
    private final boolean isDefault;

    /**
     * The return type starts as {@code void} and is set once by {@link #setReturnType}: a generic
     * method's return type can name its own type parameters, which need this element to exist.
     */
    KExecutableElement(ElementKind kind, String name, Set<Modifier> modifiers, boolean varArgs,
                       boolean isDefault) {
        super(kind, name, modifiers);
        this.varArgs = varArgs;
        this.isDefault = isDefault;
    }

    void setReturnType(TypeMirror returnType) {
        this.returnType = returnType;
    }

    KVariableElement addParameter(KVariableElement parameter) {
        parameters.add(adopt(parameter));
        return parameter;
    }

    void addTypeParameter(KTypeParameterElement parameter) {
        typeParameters.add(parameter);
    }

    /** Records a method this one overrides, resolved while the KSP round was live. */
    void addOverridden(KExecutableElement method) {
        overridden.add(method);
    }

    /** True when this method overrides {@code method}, directly or through a chain. */
    boolean overrides(KExecutableElement method) {
        for (KExecutableElement candidate : overridden) {
            if (candidate.equals(method) || candidate.overrides(method)) {
                return true;
            }
        }
        return false;
    }

    List<KVariableElement> parameters() {
        return parameters;
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
        return List.copyOf(typeParameters);
    }

    @Override
    public TypeMirror getReturnType() {
        return returnType;
    }

    @Override
    public List<? extends VariableElement> getParameters() {
        return List.copyOf(parameters);
    }

    @Override
    public TypeMirror getReceiverType() {
        return KTypeMirror.NoneType.NONE;
    }

    @Override
    public boolean isVarArgs() {
        return varArgs;
    }

    @Override
    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
        return List.of();
    }

    @Override
    public @Nullable AnnotationValue getDefaultValue() {
        return null;
    }

    @Override
    public TypeMirror asType() {
        return new Signature();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
        return v.visitExecutable(this, p);
    }

    /** javac's rendering: {@code name(type,type)}, the constructor under its class's simple name. */
    @Override
    public String toString() {
        javax.lang.model.element.Element owner = getEnclosingElement();
        String name = getKind() == ElementKind.CONSTRUCTOR && owner != null
            ? owner.getSimpleName().toString()
            : getSimpleName().toString();
        StringJoiner joined = new StringJoiner(",", name + "(", ")");
        for (KVariableElement parameter : parameters) {
            joined.add(parameter.asType().toString());
        }
        return joined.toString();
    }

    /** The method's type, for the rare caller that asks for it rather than for its parts. */
    private final class Signature extends KTypeMirror implements ExecutableType {
        Signature() {
            super(TypeKind.EXECUTABLE);
        }

        @Override
        public List<? extends javax.lang.model.type.TypeVariable> getTypeVariables() {
            List<javax.lang.model.type.TypeVariable> variables = new ArrayList<>();
            for (KTypeParameterElement parameter : typeParameters) {
                variables.add((javax.lang.model.type.TypeVariable) parameter.asType());
            }
            return variables;
        }

        @Override
        public TypeMirror getReturnType() {
            return returnType;
        }

        @Override
        public List<? extends TypeMirror> getParameterTypes() {
            List<TypeMirror> types = new ArrayList<>(parameters.size());
            for (KVariableElement parameter : parameters) {
                types.add(parameter.asType());
            }
            return types;
        }

        @Override
        public TypeMirror getReceiverType() {
            return KTypeMirror.NoneType.NONE;
        }

        @Override
        public List<? extends TypeMirror> getThrownTypes() {
            return List.of();
        }

        @Override
        public <R, P> R accept(TypeVisitor<R, P> v, P p) {
            return v.visitExecutable(this, p);
        }

        @Override
        public String toString() {
            return KExecutableElement.this.toString();
        }
    }
}
