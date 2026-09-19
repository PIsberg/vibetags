package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A class, interface, enum or annotation type in the stub model: a Kotlin class, object or
 * companion, a file facade ({@code FileKt}), or a synthetic {@code DefaultImpls}.
 *
 * <p>Also used, with no members and no enclosing element, as the target of a {@link KTypeMirror.Declared}
 * that names a class outside this compilation ({@code java.lang.String}); only its qualified name is
 * ever read there.
 */
final class KTypeElement extends KElement implements TypeElement {

    private final KName qualifiedName;
    private final NestingKind nesting;
    private final List<KTypeParameterElement> typeParameters = new ArrayList<>();
    private final Set<String> supertypes = new LinkedHashSet<>();
    private final @Nullable Path sourceFile;
    private TypeMirror superclass = KTypeMirror.NoneType.NONE;
    private final List<TypeMirror> interfaces = new ArrayList<>();

    KTypeElement(ElementKind kind, String simpleName, String qualifiedName, NestingKind nesting,
                 Set<Modifier> modifiers, @Nullable Path sourceFile) {
        super(kind, simpleName, modifiers);
        this.qualifiedName = new KName(qualifiedName);
        this.nesting = nesting;
        this.sourceFile = sourceFile;
    }

    /** A reference to a class this compilation does not declare, for use inside a type. */
    static KTypeElement reference(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return new KTypeElement(ElementKind.CLASS, qualifiedName.substring(dot + 1), qualifiedName,
            NestingKind.TOP_LEVEL, Set.of(Modifier.PUBLIC), null);
    }

    void addTypeParameter(KTypeParameterElement parameter) {
        typeParameters.add(parameter);
    }

    /** Records the erased qualified name of a supertype, direct or inherited. */
    void addSupertype(String erasedQualifiedName) {
        supertypes.add(erasedQualifiedName);
    }

    void setSuperclass(TypeMirror superclass) {
        this.superclass = superclass;
    }

    void addInterface(TypeMirror type) {
        interfaces.add(type);
    }

    /** True when {@code qualified} is this type or one of its supertypes. */
    boolean isSubtypeOf(String qualified) {
        return qualifiedName.contentEquals(qualified) || supertypes.contains(qualified);
    }

    /** The Kotlin source file this type was declared in, or {@code null} for a reference. */
    @Nullable Path sourceFile() {
        return sourceFile;
    }

    @Override
    public NestingKind getNestingKind() {
        return nesting;
    }

    @Override
    public Name getQualifiedName() {
        return qualifiedName;
    }

    @Override
    public TypeMirror getSuperclass() {
        return superclass;
    }

    @Override
    public List<? extends TypeMirror> getInterfaces() {
        return List.copyOf(interfaces);
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
        return List.copyOf(typeParameters);
    }

    @Override
    public TypeMirror asType() {
        List<TypeMirror> arguments = new ArrayList<>(typeParameters.size());
        for (KTypeParameterElement parameter : typeParameters) {
            arguments.add(parameter.asType());
        }
        return new KTypeMirror.Declared(this, arguments);
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
        return v.visitType(this, p);
    }

    @Override
    public String toString() {
        return qualifiedName.toString();
    }
}
