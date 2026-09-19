package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The stub model of one KSP round: the top-level types kapt would have generated a stub for, in
 * the order their sources were listed, and every element beneath them.
 */
final class StubModel {

    private final List<KTypeElement> roots;
    private final Map<String, KTypeElement> declared;
    private final List<String> dropped;

    StubModel(List<KTypeElement> roots, Map<String, KTypeElement> declared, List<String> dropped) {
        this.roots = List.copyOf(roots);
        this.declared = Map.copyOf(declared);
        this.dropped = List.copyOf(dropped);
    }

    /** One sentence per VibeTags annotation on a declaration that has no stub element. */
    List<String> dropped() {
        return dropped;
    }

    /** The top-level types, as {@code RoundEnvironment.getRootElements()} returns them. */
    List<KTypeElement> roots() {
        return roots;
    }

    /** A type this round declares, by qualified name, or {@code null}. */
    @Nullable KTypeElement type(String qualifiedName) {
        return declared.get(qualifiedName);
    }

    /** Every element in the model, depth first in declaration order. */
    List<KElement> all() {
        List<KElement> all = new ArrayList<>();
        for (KTypeElement root : roots) {
            collect(root, all);
        }
        return all;
    }

    private static void collect(KElement element, List<KElement> into) {
        into.add(element);
        for (KElement child : element.enclosedElements()) {
            collect(child, into);
        }
    }

    /** The Kotlin source file {@code element} came from, or {@code null} when it has none. */
    static @Nullable Path sourceOf(Element element) {
        for (Element current = element; current != null; current = current.getEnclosingElement()) {
            if (current instanceof KTypeElement type && type.sourceFile() != null) {
                return type.sourceFile();
            }
            if (current instanceof TypeElement && !(current instanceof KTypeElement)) {
                return null;
            }
        }
        return null;
    }
}
