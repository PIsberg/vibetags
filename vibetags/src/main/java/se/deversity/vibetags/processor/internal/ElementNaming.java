package se.deversity.vibetags.processor.internal;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

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
     * Returns a fully-qualified path for an element. For FIELD/METHOD/CONSTRUCTOR the enclosing
     * type's FQN is prepended; otherwise the element's own toString is used.
     */
    public static String elementPath(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.FIELD || kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
            Element enclosing = element.getEnclosingElement();
            if (enclosing != null) {
                return enclosing.toString() + "." + element.toString();
            }
        }
        return element.toString();
    }

    /**
     * Short display name suitable for llms.txt link text. For FIELD/METHOD/CONSTRUCTOR returns
     * "EnclosingSimpleName.memberSig"; for types just the simple name.
     */
    public static String elementDisplayName(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.FIELD || kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
            Element enclosing = element.getEnclosingElement();
            if (enclosing != null) {
                return enclosing.getSimpleName() + "." + element.toString();
            }
        }
        return element.getSimpleName().toString();
    }
}
