package se.deversity.vibetags.processor.internal;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders an element's <em>structural</em> shape as a stable string: the part of it that an
 * enforcing guardrail is about.
 *
 * <p>The opt-in enforcing mode (<a href="https://github.com/PIsberg/vibetags/issues/284">issue
 * #284</a>) compares this against a committed baseline. What it captures is deliberately narrow —
 * exactly what the processor can <em>prove</em> from the javac element model, and nothing that
 * depends on method bodies:
 *
 * <ul>
 *   <li>a method or constructor → its name, parameter types, return type and checked exceptions;</li>
 *   <li>a type → its supertypes plus the signatures of its visible (public/protected) members,
 *       sorted, so the API surface is the thing under guard rather than the file's byte content;</li>
 *   <li>a field → its declared type.</li>
 * </ul>
 *
 * <p>Bodies, comments, formatting and private members are all invisible here, on purpose: an
 * enforcement that fires when a maintainer reformats a locked file is one that gets switched off
 * within a week. What it will not miss is a signature change, which is the thing an
 * {@code @AIContract} or {@code @AIPublicAPI} actually promises.
 *
 * <p>Deliberately in {@code internal} and fed a javac {@link Element}: the rendering half of the
 * processor must stay compiler-free, so the result is carried forward as plain data on
 * {@code TaggedElement.signature()}.
 */
public final class ElementSignature {

    private ElementSignature() {
    }

    /** The structural signature of {@code element}, or {@code ""} when it has no meaningful one. */
    public static String of(Element element) {
        try {
            switch (element.getKind()) {
                case METHOD:
                case CONSTRUCTOR:
                    return executable((ExecutableElement) element);
                case FIELD:
                case ENUM_CONSTANT:
                case RECORD_COMPONENT:
                case PARAMETER:
                    return variable((VariableElement) element);
                case CLASS:
                case INTERFACE:
                case ENUM:
                case RECORD:
                case ANNOTATION_TYPE:
                    return type((TypeElement) element);
                default:
                    return "";
            }
        } catch (RuntimeException e) {
            // Enforcement is advisory scaffolding; an unusual element must never break a compile.
            return "";
        }
    }

    private static String executable(ExecutableElement method) {
        StringJoiner params = new StringJoiner(",", "(", ")");
        for (VariableElement parameter : method.getParameters()) {
            params.add(erase(parameter.asType()));
        }
        StringBuilder sb = new StringBuilder()
            .append(method.getSimpleName())
            .append(params)
            .append(':')
            .append(erase(method.getReturnType()));
        List<String> thrown = new ArrayList<>();
        for (TypeMirror t : method.getThrownTypes()) {
            thrown.add(erase(t));
        }
        if (!thrown.isEmpty()) {
            thrown.sort(String::compareTo); // declaration order is not part of the contract
            sb.append(" throws ").append(String.join(",", thrown));
        }
        return sb.toString();
    }

    private static String variable(VariableElement field) {
        return erase(field.asType()) + " " + field.getSimpleName();
    }

    /**
     * A type's visible API surface: supertypes plus every public or protected member, sorted.
     * Private and package-private members are excluded — changing them cannot break a caller, and
     * including them would make the baseline churn on ordinary refactoring.
     */
    private static String type(TypeElement typeElement) {
        List<String> members = new ArrayList<>();
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (!isVisible(enclosed)) {
                continue;
            }
            String member = of(enclosed);
            if (!member.isEmpty()) {
                members.add(member);
            }
        }
        members.sort(String::compareTo); // source order is not part of the contract either
        StringJoiner supertypes = new StringJoiner(",");
        supertypes.add(erase(typeElement.getSuperclass()));
        List<String> interfaces = new ArrayList<>();
        for (TypeMirror t : typeElement.getInterfaces()) {
            interfaces.add(erase(t));
        }
        interfaces.sort(String::compareTo);
        interfaces.forEach(supertypes::add);
        return "extends[" + supertypes + "] members[" + String.join(";", members) + "]";
    }

    private static boolean isVisible(Element element) {
        return element.getModifiers().contains(Modifier.PUBLIC)
            || element.getModifiers().contains(Modifier.PROTECTED);
    }

    /**
     * A type's name as a plain string. Uses {@code toString()} rather than {@code Types.erasure} so
     * this needs no {@code ProcessingEnvironment} and stays usable from anywhere in the collector;
     * generic arguments are part of the signature a caller binds to, so keeping them is correct.
     */
    private static String erase(TypeMirror type) {
        return type == null ? "" : type.toString();
    }
}
