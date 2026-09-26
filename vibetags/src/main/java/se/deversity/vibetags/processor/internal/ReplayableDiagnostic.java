package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * A diagnostic validation raised in a live round, kept in a form a later build can repeat (#856).
 *
 * <p>The early exit (#834) skips the collection walk, and validation's diagnostics come from the
 * walk, so a skipped build has to print them from the record the last clean build left, or a
 * rebuild would look warning-free for a source that still has the problem and a {@code -Werror}
 * build would start passing. The record lives beside the source digest in {@code .vibetags-cache}.
 *
 * <p><strong>Anchors.</strong> javac prefixes an anchored diagnostic with the element's file, line
 * and column, and the replay has to land on the same ones. An element cannot be stored, so it is
 * named: a type by its qualified name, a member by its enclosing type, its kind and its
 * {@code toString()} (which carries a method's parameter types, so overloads stay apart), a
 * parameter by its method and position, a package by its name. The next build resolves the name
 * back through {@link Elements} while its first round is live. The digest guarantees the sources
 * are byte-identical, so the name resolves to the same declaration; an element that cannot be named
 * (a type parameter) or resolved replays without a position, and the count stays the same.
 */
public record ReplayableDiagnostic(Diagnostic.Kind kind, String message, @Nullable String anchor,
                                   @Nullable String annotation) {

    private static final char SEP = '\n';
    private static final String NONE = "-";
    private static final String TYPE = "type";
    private static final String PACKAGE = "package";
    private static final String MEMBER = "member";
    private static final String PARAM = "param";
    private static final int ENCODED_FIELDS = 4;

    /** The diagnostic javac was just handed, with its anchor and annotation named rather than held. */
    public static ReplayableDiagnostic of(Diagnostic.Kind kind, CharSequence message, @Nullable Element element,
                                          @Nullable AnnotationMirror mirror) {
        String annotation = null;
        if (mirror != null && mirror.getAnnotationType().asElement() instanceof TypeElement type) {
            annotation = type.getQualifiedName().toString();
        }
        return new ReplayableDiagnostic(kind, message.toString(), element == null ? null : anchorOf(element),
            annotation);
    }

    /** One line, no line breaks: the kind, then the anchor, annotation and message, each base64. */
    public String encode() {
        return kind.name() + ' ' + field(anchor) + ' ' + field(annotation) + ' ' + field(message);
    }

    /** {@link #encode()}'s line read back, or {@code null} when it is not one. */
    public static @Nullable ReplayableDiagnostic decode(String line) {
        String[] parts = line.split(" ", -1);
        if (parts.length != ENCODED_FIELDS) {
            return null;
        }
        try {
            Diagnostic.Kind kind = Diagnostic.Kind.valueOf(parts[0]);
            String message = unfield(parts[3]);
            if (message == null) {
                return null;
            }
            return new ReplayableDiagnostic(kind, message, unfield(parts[1]), unfield(parts[2]));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Prints this diagnostic again, anchored where the original was when the anchor still resolves.
     * An anchor that does not resolve prints without one: the same text and the same count, which
     * is what {@code -Werror} and anyone reading the build output depend on.
     */
    public void replay(Messager messager, @Nullable Elements elements) {
        Element element = anchor == null || elements == null ? null : resolve(elements, anchor);
        if (element == null) {
            messager.printMessage(kind, message);
            return;
        }
        AnnotationMirror mirror = annotation == null ? null : mirrorOf(element, annotation);
        if (mirror == null) {
            messager.printMessage(kind, message, element);
        } else {
            messager.printMessage(kind, message, element, mirror);
        }
    }

    /** A name for {@code element} that {@link #resolve} turns back into it, or {@code null}. */
    static @Nullable String anchorOf(Element element) {
        if (element instanceof PackageElement pkg) {
            return PACKAGE + SEP + pkg.getQualifiedName();
        }
        if (element instanceof TypeElement type) {
            String name = type.getQualifiedName().toString();
            return name.isEmpty() ? null : TYPE + SEP + name; // local and anonymous types have none
        }
        Element owner = element.getEnclosingElement();
        if (element instanceof VariableElement && element.getKind() == ElementKind.PARAMETER
                && owner instanceof ExecutableElement method) {
            String methodAnchor = anchorOf(method);
            int index = method.getParameters().indexOf(element);
            return methodAnchor == null || index < 0 ? null
                : PARAM + methodAnchor.substring(MEMBER.length()) + SEP + index;
        }
        if (owner instanceof TypeElement type && !type.getQualifiedName().toString().isEmpty()
                && element.getKind() != ElementKind.TYPE_PARAMETER) {
            return MEMBER + SEP + type.getQualifiedName() + SEP + element.getKind() + SEP + element;
        }
        return null;
    }

    /** The element {@code anchor} names in this build, or {@code null} when there is none. */
    static @Nullable Element resolve(Elements elements, String anchor) {
        String[] parts = anchor.split(String.valueOf(SEP), -1);
        try {
            switch (parts[0]) {
                case TYPE -> {
                    return parts.length == 2 ? elements.getTypeElement(parts[1]) : null;
                }
                case PACKAGE -> {
                    return parts.length == 2 ? elements.getPackageElement(parts[1]) : null;
                }
                case MEMBER -> {
                    return parts.length == 4 ? member(elements, parts[1], parts[2], parts[3]) : null;
                }
                case PARAM -> {
                    if (parts.length != 5
                            || !(member(elements, parts[1], parts[2], parts[3]) instanceof ExecutableElement method)) {
                        return null;
                    }
                    int index = Integer.parseInt(parts[4]);
                    List<? extends VariableElement> params = method.getParameters();
                    return index >= 0 && index < params.size() ? params.get(index) : null;
                }
                default -> {
                    return null;
                }
            }
        } catch (RuntimeException unresolvable) {
            return null; // a malformed anchor, or a compiler that will not answer
        }
    }

    private static @Nullable Element member(Elements elements, String owner, String kind, String name) {
        TypeElement type = elements.getTypeElement(owner);
        if (type == null) {
            return null;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind().name().equals(kind) && enclosed.toString().equals(name)) {
                return enclosed;
            }
        }
        return null;
    }

    private static @Nullable AnnotationMirror mirrorOf(Element element, String annotation) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof QualifiedNameable type
                    && type.getQualifiedName().contentEquals(annotation)) {
                return mirror;
            }
        }
        return null;
    }

    private static String field(@Nullable String value) {
        return value == null ? NONE : Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** {@link #field}'s inverse; throws IllegalArgumentException on text that is neither. */
    private static @Nullable String unfield(String value) {
        return NONE.equals(value) ? null : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
