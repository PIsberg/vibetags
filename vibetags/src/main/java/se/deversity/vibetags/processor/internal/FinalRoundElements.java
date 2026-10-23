package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import java.util.List;

/**
 * Looks an element collected in an earlier round up again in the final one, when its signature
 * names a type that did not exist yet (#989).
 *
 * <p>A member whose signature names a type another processor generates (Immutables'
 * {@code ImmutableMoney}, hibernate-processor's {@code Order_}) is collected in the round before
 * that type exists, so javac hands over an error type, which renders as the bare name the source
 * wrote. The collector keeps that element object to the end, but javac enters each new round's
 * members as fresh symbols, so the round-1 member keeps its error type for good. The path built from it,
 * {@code total(ImmutableMoney)} beside {@code toDto(com.example.Customer)}, is the element's
 * identity in {@code .vibetags-locks}, granular rule names and the enforcement baseline.
 *
 * <p>By the final round every generated type exists, and looking the enclosing type up by name
 * returns a symbol whose members javac resolves against them. Only an element whose own types hold
 * an error type is looked up, so every other element, which is nearly all of them, renders from
 * exactly the object it always did. A lookup that finds nothing, or more than one candidate, keeps
 * the collected element: an unqualified name is the old behaviour, a wrong member would not be.
 */
public final class FinalRoundElements {

    private final Elements elements;

    public FinalRoundElements(Elements elements) {
        this.elements = elements;
    }

    /** {@code e} as the final round sees it, or {@code e} itself when nothing in it was unresolved. */
    public Element current(Element e) {
        try {
            return switch (e.getKind()) {
                case METHOD, CONSTRUCTOR -> currentMember(e);
                case FIELD, ENUM_CONSTANT, RECORD_COMPONENT -> hasError(e.asType()) ? currentMember(e) : e;
                case PARAMETER -> currentParameter((VariableElement) e);
                default -> e;
            };
        } catch (RuntimeException ex) {
            // Naming is advisory: an unusual element keeps the name it had rather than failing a build.
            return e;
        }
    }

    /**
     * As {@link #current}, and for a type, the type as the final round sees it when one of its
     * members' types was unresolved: a type's structural signature renders every visible member,
     * so it goes stale the same way a member's path does. Kept apart from {@link #current} because
     * only the opt-in enforcing mode reads signatures, and checking a type walks its members.
     */
    public Element forSignature(Element e) {
        if (!(e instanceof TypeElement type)) {
            return current(e);
        }
        try {
            for (Element member : type.getEnclosedElements()) {
                if (member instanceof ExecutableElement executable ? hasError(executable) : hasError(member.asType())) {
                    TypeElement fresh = elements.getTypeElement(type.getQualifiedName());
                    return fresh == null ? e : fresh;
                }
            }
        } catch (RuntimeException ex) {
            return e;
        }
        return e;
    }

    private Element currentParameter(VariableElement parameter) {
        Element owner = parameter.getEnclosingElement();
        if (!(owner instanceof ExecutableElement executable)) {
            return parameter;
        }
        Element fresh = currentMember(executable);
        if (fresh.equals(executable) || !(fresh instanceof ExecutableElement freshExecutable)) {
            return parameter;
        }
        int index = executable.getParameters().indexOf(parameter);
        return index < 0 ? parameter : freshExecutable.getParameters().get(index);
    }

    private Element currentMember(Element member) {
        if (member instanceof ExecutableElement executable && !hasError(executable)) {
            return member;
        }
        Element owner = member.getEnclosingElement();
        if (!(owner instanceof TypeElement type)) {
            return member;
        }
        // Not compared with type: javac keeps a source class's symbol across rounds and re-enters
        // only its members, so the type is usually the same object with new members inside it.
        TypeElement fresh = elements.getTypeElement(type.getQualifiedName());
        if (fresh == null) {
            return member;
        }
        Element match = null;
        for (Element candidate : fresh.getEnclosedElements()) {
            if (sameMember(member, candidate)) {
                if (match != null) {
                    return member; // ambiguous: keep the old name rather than guess
                }
                match = candidate;
            }
        }
        return match == null ? member : match;
    }

    private static boolean sameMember(Element stale, Element fresh) {
        if (stale.getKind() != fresh.getKind() || !stale.getSimpleName().contentEquals(fresh.getSimpleName())) {
            return false;
        }
        if (!(stale instanceof ExecutableElement s) || !(fresh instanceof ExecutableElement f)) {
            return true; // a field's name is unique within its type
        }
        List<? extends VariableElement> sp = s.getParameters();
        List<? extends VariableElement> fp = f.getParameters();
        if (sp.size() != fp.size()) {
            return false;
        }
        for (int i = 0; i < sp.size(); i++) {
            if (!sameType(sp.get(i).asType(), fp.get(i).asType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two renderings name the same type once qualifiers are set aside, which is all an
     * error type can be compared on: it carries the name as written, with no package.
     */
    private static boolean sameType(TypeMirror stale, TypeMirror fresh) {
        String s = stale.toString();
        String f = fresh.toString();
        return s.equals(f) || unqualified(s).equals(unqualified(f));
    }

    /**
     * {@code rendered} with every package and enclosing-type qualifier dropped:
     * {@code java.util.List<com.example.Money>} becomes {@code List<Money>}. A linear scan rather
     * than a regular expression, for the reason {@code ElementNaming.stripAnnotations} gives.
     */
    private static String unqualified(String rendered) {
        StringBuilder out = new StringBuilder(rendered.length());
        int start = 0;
        for (int i = 0; i < rendered.length(); i++) {
            char c = rendered.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                continue;
            }
            if (c != '.' || i == start) {
                out.append(rendered, start, i).append(c);
            }
            start = i + 1;
        }
        return out.append(rendered, start, rendered.length()).toString();
    }

    private static boolean hasError(ExecutableElement executable) {
        if (hasError(executable.getReturnType())) {
            return true;
        }
        for (VariableElement parameter : executable.getParameters()) {
            if (hasError(parameter.asType())) {
                return true;
            }
        }
        for (TypeMirror thrown : executable.getThrownTypes()) {
            if (hasError(thrown)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code type} is, or has as a type argument, array component or bound, an error type. */
    static boolean hasError(@Nullable TypeMirror type) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        if (type instanceof ArrayType array) {
            return hasError(array.getComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            return hasError(wildcard.getExtendsBound()) || hasError(wildcard.getSuperBound());
        }
        if (type instanceof DeclaredType declared) {
            for (TypeMirror argument : declared.getTypeArguments()) {
                if (hasError(argument)) {
                    return true;
                }
            }
        }
        return false;
    }
}
