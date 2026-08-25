package se.deversity.vibetags.processor.internal;

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
        if (element.getKind() == ElementKind.FIELD) {
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
     * Returns a fully-qualified path for an element. For FIELD/METHOD/CONSTRUCTOR the enclosing
     * type's FQN is prepended; for PARAMETER the enclosing executable's path is prepended with a
     * {@code #} separator (e.g. {@code com.example.Foo.export(java.lang.String)#filePath});
     * otherwise the element's own toString is used.
     */
    public static String elementPath(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.PARAMETER) {
            Element executable = element.getEnclosingElement();
            if (executable != null) {
                return elementPath(executable) + "#" + element.getSimpleName();
            }
        }
        if (kind == ElementKind.FIELD || kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
            Element enclosing = element.getEnclosingElement();
            if (enclosing != null) {
                return qualifiedName(enclosing) + "." + memberSignature(element);
            }
        }
        return qualifiedName(element);
    }

    /**
     * Short display name suitable for llms.txt link text. For FIELD/METHOD/CONSTRUCTOR returns
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
        if (kind == ElementKind.FIELD || kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
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
}
