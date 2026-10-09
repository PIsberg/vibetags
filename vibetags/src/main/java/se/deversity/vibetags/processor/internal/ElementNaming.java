package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Helpers for deriving display strings from {@link Element} instances.
 * Pure functions — no mutable state, safe to call from any thread.
 */
public final class ElementNaming {

    private ElementNaming() {}

    /**
     * Walks up the element hierarchy to find the nearest {@link TypeElement} (class/interface)
     * or package element. Used to consolidate granular rules at file or package level.
     */
    public static Element owningElement(Element e) {
        Element current = e;
        while (current != null) {
            ElementKind kind = current.getKind();
            if (current instanceof TypeElement || (kind != null && kind == ElementKind.PACKAGE)) {
                return current;
            }
            current = current.getEnclosingElement();
        }
        return e;
    }

    /**
     * For a nested type, the simple name of the outermost type enclosing it, which names the source
     * file that declares it; {@code ""} for a top-level type and for anything that is not a type.
     * A local class counts as nested in the type whose method declares it.
     */
    public static String fileStem(Element e) {
        if (!(e instanceof TypeElement)) {
            return "";
        }
        Element outermost = e;
        for (Element at = e.getEnclosingElement(); at != null && at.getKind() != ElementKind.PACKAGE
                && at.getKind() != ElementKind.MODULE; at = at.getEnclosingElement()) {
            if (at instanceof TypeElement) {
                outermost = at;
            }
        }
        return outermost.equals(e) ? "" : outermost.getSimpleName().toString();
    }

    /** The outermost type enclosing {@code e}, {@code e} itself for a top-level type, or {@code null} for a package. */
    public static @Nullable TypeElement outermostType(Element e) {
        TypeElement outermost = null;
        for (Element at = e; at != null && at.getKind() != ElementKind.PACKAGE
                && at.getKind() != ElementKind.MODULE; at = at.getEnclosingElement()) {
            if (at instanceof TypeElement type) {
                outermost = type;
            }
        }
        return outermost;
    }

    /**
     * The name of the source file the top-level type {@code outermost} is written in, extension
     * included, which is what a glob for its code has to name (#939).
     *
     * <p>{@code recorded} is the name the round reported for the file javac or the front end read,
     * when it could say. Under KSP that is the Kotlin file itself ({@code Billing.kt}), and it is
     * used as it is. Under kapt and Groovy's stub generation it is a generated {@code .java} stub,
     * so the stub's markers decide: kapt keeps {@code @kotlin.Metadata} on every class, and a
     * Groovy class implements {@code groovy.lang.GroovyObject} (a trait carries
     * {@code @groovy.transform.Trait}). A stub does not say what its source file was called, so the
     * type's own name stands in for it, which is the convention both languages follow for a file
     * declaring one class; a Kotlin file facade ({@code BillingKt}, metadata kind 2) drops its
     * {@code Kt}. Otherwise the recorded name, which for Java can differ from the type's own name
     * (a second top-level type in {@code Ledger.java}), and failing that {@code <Type>.java}.
     */
    public static String sourceFileName(TypeElement outermost, @Nullable String recorded) {
        String stem = outermost.getSimpleName().toString();
        int kotlinKind = kotlinMetadataKind(outermost);
        if (kotlinKind > 0) {
            if (recorded != null && recorded.endsWith(".kt")) {
                return recorded;
            }
            if (kotlinKind == KOTLIN_FILE_FACADE && stem.endsWith("Kt") && stem.length() > 2) {
                stem = stem.substring(0, stem.length() - 2);
            }
            return stem + ".kt";
        }
        if (isGroovy(outermost)) {
            return recorded != null && recorded.endsWith(".groovy") ? recorded : stem + ".groovy";
        }
        return recorded != null ? recorded : stem + ".java";
    }

    /** {@code kotlin.Metadata}'s {@code k} for a file facade: the class holding a file's top-level functions. */
    private static final int KOTLIN_FILE_FACADE = 2;

    /** The {@code k} of {@code type}'s {@code @kotlin.Metadata}, 1 when it is not set, 0 when there is none. */
    private static int kotlinMetadataKind(TypeElement type) {
        for (var mirror : type.getAnnotationMirrors()) {
            Element annotation = mirror.getAnnotationType().asElement();
            if (annotation instanceof TypeElement t && "kotlin.Metadata".contentEquals(t.getQualifiedName())) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("k")
                        && entry.getValue().getValue() instanceof Number kind) {
                        return kind.intValue();
                    }
                }
                return 1;
            }
        }
        return 0;
    }

    /** Whether {@code type} is a Groovy class or trait as groovyc's Java stub declares it. */
    private static boolean isGroovy(TypeElement type) {
        for (TypeMirror iface : type.getInterfaces()) {
            if (iface instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement t
                && "groovy.lang.GroovyObject".contentEquals(t.getQualifiedName())) {
                return true;
            }
        }
        for (var mirror : type.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement t
                && "groovy.transform.Trait".contentEquals(t.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the granular rule filename stem (minus extension) for an element: its FQN with every
     * character outside {@code [A-Za-z0-9-]} replaced by {@code -} (dots included). This is the
     * single source of truth shared by {@link GranularRulesWriter} (which names the scoped files)
     * and the aggregate scoped-rules index (which must point at exactly those filenames) — keeping
     * both in lock-step so a pointer can never drift from the file it references.
     */
    public static String granularQName(Element element) {
        return qualifiedName(element).replace('.', '-').replaceAll("[^a-zA-Z0-9-]", "-");
    }

    /**
     * Takes type-use annotations back off a rendering that came from {@code toString()}: an
     * {@code @}, the qualified annotation name, any parenthesised arguments, and the space javac
     * writes after it.
     *
     * <p>Only reached for the types with no structural route: an error type under an incomplete
     * classpath, an intersection, a language feature newer than this code. Those are exactly the
     * cases where nobody is watching, which is why they are stripped rather than trusted, and the
     * third-party corpus generates real output for jspecify-annotated libraries to check it.
     *
     * <p>Scanned by hand rather than with a regular expression. The obvious pattern here nests a
     * quantifier inside an optional group, which SpotBugs' ReDOS detector rejects, and a linear
     * scan over a type name is both faster and easier to read than the possessive-quantifier
     * version that would satisfy it.
     */
    private static String stripAnnotations(String rendered) {
        if (rendered.indexOf('@') < 0) {
            return rendered;
        }
        StringBuilder out = new StringBuilder(rendered.length());
        int i = 0;
        while (i < rendered.length()) {
            char c = rendered.charAt(i);
            if (c != '@') {
                out.append(c);
                i++;
                continue;
            }
            i++; // the '@'
            while (i < rendered.length()
                   && (Character.isLetterOrDigit(rendered.charAt(i))
                       || rendered.charAt(i) == '_' || rendered.charAt(i) == '.')) {
                i++;
            }
            if (i < rendered.length() && rendered.charAt(i) == '(') {
                int close = rendered.indexOf(')', i);
                i = close < 0 ? rendered.length() : close + 1;
            }
            while (i < rendered.length() && Character.isWhitespace(rendered.charAt(i))) {
                i++;
            }
        }
        return out.toString();
    }

    /**
     * The fully-qualified name of a type or package, without going through
     * {@link Object#toString()}.
     *
     * <p>{@code Element.toString()} is specified as "a string representation of this element" and
     * the format is left to the implementation. javac and ECJ agree for types and packages, but
     * relying on that agreement is relying on a coincidence, so the qualified name is read from
     * the API that promises it. Falls back to {@code toString()} for the element kinds that are
     * not {@link QualifiedNameable} — the same behaviour as before for those.
     */
    private static String qualifiedName(Element element) {
        if (element instanceof QualifiedNameable nameable) {
            Name qualified = nameable.getQualifiedName();
            if (qualified != null) {
                return qualified.toString();
            }
        }
        return element.toString();
    }

    /**
     * A member's signature: the field's name, or the method/constructor's name with its parameter
     * types in parentheses.
     *
     * <p>This is the part that used to be {@code element.toString()} and could not stay that way.
     * Under javac a method renders as {@code validateOrder(java.util.Map<java.lang.String,java.lang.Object>)};
     * under ECJ the same element renders as
     * {@code public boolean validateOrder(Map<java.lang.String,java.lang.Object>) } — modifiers,
     * return type, a trailing space, and an unqualified raw type. The string is the element's
     * identity: {@code .vibetags-locks} records it and the {@code action/locked-files} guard
     * matches a pull request's diff against it, and {@link #granularQName} turns it into a rule
     * filename. An identity that depends on which compiler ran is a lock that does not match.
     *
     * <p>The format reproduced here is javac's, deliberately: javac is what the committed fixtures
     * and every consumer's generated files were produced by, so converging on it moves ECJ output
     * onto javac's and leaves javac's own output byte-identical. {@code ElementNamingFormatParityTest}
     * pins that by compiling a fixture and asserting this method agrees with {@code toString()} on
     * every member, so the day javac changes its rendering the test says so rather than the
     * generated files quietly moving.
     */
    private static String memberSignature(Element element) {
        if (isVariableMember(element.getKind())) {
            return simpleNameOf(element);
        }
        if (!(element instanceof ExecutableElement executable)) {
            return element.toString();
        }
        // javac prints a constructor under its class's simple name, not under "<init>". A
        // constructor always has an enclosing type, but getEnclosingElement() is @Nullable in
        // general, so the absent case falls back to the element's own simple name rather than
        // throwing inside somebody else's build.
        Element owner = element.getEnclosingElement();
        String name = element.getKind() == ElementKind.CONSTRUCTOR && owner != null
            ? simpleNameOf(owner)
            : simpleNameOf(element);

        // A generic method carries its type parameters in front of the name — javac renders
        // <T>typeVariable(T,java.util.List<T>), by their names only, bounds omitted.
        StringBuilder signature = new StringBuilder();
        List<? extends TypeParameterElement> typeParameters = executable.getTypeParameters();
        if (!typeParameters.isEmpty()) {
            StringJoiner declared = new StringJoiner(",", "<", ">");
            for (TypeParameterElement parameter : typeParameters) {
                declared.add(simpleNameOf(parameter));
            }
            signature.append(declared);
        }
        signature.append(name);

        List<? extends VariableElement> parameters = executable.getParameters();
        StringJoiner joined = new StringJoiner(",", "(", ")");
        for (int i = 0; i < parameters.size(); i++) {
            boolean varargs = executable.isVarArgs() && i == parameters.size() - 1;
            joined.add(typeString(parameters.get(i).asType(), varargs));
        }
        return signature.append(joined).toString();
    }

    /**
     * Renders a type the way javac's own {@code toString()} does: qualified names for declared
     * types, type arguments kept and comma-separated with no spaces, and a trailing {@code ...}
     * for the last parameter of a varargs method.
     *
     * <p><b>With one deliberate exception: type-use annotations are dropped.</b> javac renders an
     * annotated parameter as {@code java.lang.@org.jspecify.annotations.Nullable String}, and
     * reproducing that would put the annotation into the element's identity. That identity is
     * matched against a pull request's diff by {@code action/locked-files} and turned into a rule
     * filename by {@link #granularQName}, where it becomes
     * {@code ...parse-java-lang--org-jspecify-annotations-Nullable-String-}. Adding or removing a
     * {@code @Nullable} would then rename a committed rule file and stop a lock matching, for a
     * change that does not alter the signature at all.
     *
     * <p>So the identity is the signature, not its annotations. This is checked rather than
     * assumed: the third-party corpus (see {@code corpus/README.md}) compiles jspecify-annotated
     * libraries and asserts that annotations are the <em>only</em> thing this renders differently
     * from javac.
     */
    private static String typeString(TypeMirror type, boolean varargs) {
        if (varargs && type instanceof ArrayType array) {
            return typeString(array.getComponentType(), false) + "...";
        }
        if (type instanceof ArrayType array) {
            return typeString(array.getComponentType(), false) + "[]";
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getExtendsBound() != null) {
                return "? extends " + typeString(wildcard.getExtendsBound(), false);
            }
            if (wildcard.getSuperBound() != null) {
                return "? super " + typeString(wildcard.getSuperBound(), false);
            }
            return "?";
        }
        if (type instanceof DeclaredType declared) {
            StringBuilder sb = new StringBuilder(qualifiedName(declared.asElement()));
            List<? extends TypeMirror> arguments = declared.getTypeArguments();
            if (!arguments.isEmpty()) {
                StringJoiner joined = new StringJoiner(",", "<", ">");
                for (TypeMirror argument : arguments) {
                    joined.add(typeString(argument, false));
                }
                sb.append(joined);
            }
            return sb.toString();
        }
        if (type instanceof TypeVariable variable) {
            // Read through to the declaring element rather than taking toString(). A type
            // variable at an annotated use renders as "@org.jspecify.annotations.Nullable A",
            // and that annotation would then be part of the element's identity - the one thing
            // the whole derivation exists to avoid. The declared parameters get the same
            // treatment above; this is the use site.
            Element declared = variable.asElement();
            String name = declared == null ? "" : simpleNameOf(declared);
            return name.isEmpty() ? stripAnnotations(type.toString()) : name;
        }
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return type.getKind().name().toLowerCase(Locale.ROOT);
        }
        // Error types under an incomplete classpath, intersections, and anything a future
        // language version adds. toString() is all that is left, with any annotations taken back
        // off so an exotic type cannot smuggle one into the identity either.
        return stripAnnotations(type.toString());
    }

    /**
     * Returns a fully-qualified path for an element. For a member (see {@link #isMember}) the enclosing
     * type's FQN is prepended; for PARAMETER the enclosing executable's path is prepended with a
     * {@code #} separator (e.g. {@code com.example.Foo.export(java.lang.String)#filePath});
     * otherwise the element's own toString is used.
     *
     * <p>Enum constants and record components count as members for the same reason fields do:
     * their {@code toString()} is the bare name, so without the enclosing type the locked
     * {@code ACTIVE} of two different enums collapsed into one element and one guardrail was
     * silently dropped.
     */
    public static String elementPath(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.PARAMETER) {
            Element executable = element.getEnclosingElement();
            if (executable != null) {
                return elementPath(executable) + "#" + element.getSimpleName();
            }
        }
        if (isMember(kind)) {
            Element enclosing = element.getEnclosingElement();
            if (enclosing != null) {
                return qualifiedName(enclosing) + "." + memberSignature(element);
            }
        }
        return qualifiedName(element);
    }

    /**
     * Short display name suitable for llms.txt link text. For a member (see {@link #isMember}) returns
     * "EnclosingSimpleName.memberSig"; for PARAMETER the enclosing executable's display name is
     * prepended with a {@code #} separator; for types just the simple name.
     */
    public static String elementDisplayName(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.PARAMETER) {
            Element executable = element.getEnclosingElement();
            if (executable != null) {
                return elementDisplayName(executable) + "#" + simpleNameOf(element);
            }
        }
        if (isMember(kind)) {
            Element enclosing = element.getEnclosingElement();
            if (enclosing != null) {
                return simpleNameOf(enclosing) + "." + memberSignature(element);
            }
        }
        return simpleNameOf(element);
    }

    /**
     * The element's simple name as a String, or {@code ""} when the compiler does not supply one.
     * The empty case is real: unnamed packages have no simple name, and mocked elements in unit
     * tests return none — neither is worth an NPE inside somebody else's build.
     */
    public static String simpleNameOf(Element element) {
        Name name = element.getSimpleName();
        return name != null ? name.toString() : "";
    }

    /** A member whose signature is just its name: a field, an enum constant, a record component. */
    private static boolean isVariableMember(ElementKind kind) {
        return kind == ElementKind.FIELD
            || kind == ElementKind.ENUM_CONSTANT
            || kind == ElementKind.RECORD_COMPONENT;
    }

    /** A member of a type, whose path is the enclosing type FQN plus its signature. */
    private static boolean isMember(ElementKind kind) {
        return isVariableMember(kind) || kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR;
    }
}
