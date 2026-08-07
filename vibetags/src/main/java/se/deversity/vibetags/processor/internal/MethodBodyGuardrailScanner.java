package se.deversity.vibetags.processor.internal;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

/**
 * Warns about guardrail annotations written where annotation processing cannot see them: on local
 * classes and on members of anonymous classes.
 *
 * <p>JSR 269 processing walks declarations, not statements. A local or anonymous declaration never
 * reaches {@code getElementsAnnotatedWith}, so a {@code @AILocked} inside a method body compiles
 * and then does nothing at all — no generated entry, no validation, no diagnostic. That silent
 * no-op is the worst failure shape this project knows, and the author finds out only when the
 * guardrail they relied on turns out never to have existed. The element model cannot fix this, but
 * the javac Tree API can <em>see</em> it, which is enough to say so at compile time.
 *
 * <p>Best-effort by construction: the Tree API is javac-only (the Gradle incremental-processing
 * wrapper is unwrapped the same way {@link SourcePositionResolver} does), and a compilation unit is
 * only scanned when it imports {@code se.deversity.vibetags.annotations} — so a build with no
 * guardrails in it pays one import check per file and nothing more. A unit that names an annotation
 * fully qualified without importing it is still caught by the qualified-name match inside the scan;
 * one that does neither is missed, which is the acceptable corner of a diagnostic that must never
 * produce a false positive.
 */
public final class MethodBodyGuardrailScanner {

    private static final String ANNOTATIONS_PACKAGE = "se.deversity.vibetags.annotations";

    /** Simple names of every registered guardrail annotation, for the import-based match. */
    private static final Set<String> SIMPLE_NAMES = simpleNames();

    private static Set<String> simpleNames() {
        Set<String> names = new HashSet<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            names.add(type.getSimpleName());
        }
        return Set.copyOf(names);
    }

    private final @Nullable Trees trees;

    private MethodBodyGuardrailScanner(@Nullable Trees trees) {
        this.trees = trees;
    }

    /** A scanner for {@code env}, or a no-op one when no compiler exposes the Tree API. */
    public static MethodBodyGuardrailScanner forEnv(ProcessingEnvironment env) {
        return new MethodBodyGuardrailScanner(SourcePositionResolver.treesFor(env));
    }

    /** A scanner that never reports — for tests and non-javac environments. */
    public static MethodBodyGuardrailScanner noop() {
        return new MethodBodyGuardrailScanner(null);
    }

    /**
     * Scans this round's root elements for guardrail annotations on local or anonymous
     * declarations, reporting each as a WARNING anchored at the annotation itself.
     */
    public void scanAndWarn(RoundEnvironment roundEnv) {
        if (trees == null) {
            return;
        }
        Set<? extends Element> roots;
        try {
            roots = roundEnv.getRootElements();
        } catch (RuntimeException e) {
            return; // mocked round environments may not stub this
        }
        if (roots == null) {
            return;
        }
        for (Element root : roots) {
            try {
                TreePath path = trees.getPath(root);
                if (path == null) {
                    continue;
                }
                CompilationUnitTree unit = path.getCompilationUnit();
                new BodyScanner(unit, importsGuardrails(unit)).scan(path.getLeaf(), null);
            } catch (RuntimeException ignored) {
                // Advisory best-effort: an unscannable tree must never affect the build.
            }
        }
    }

    private static boolean importsGuardrails(CompilationUnitTree unit) {
        for (ImportTree imp : unit.getImports()) {
            Tree qualified = imp.getQualifiedIdentifier();
            if (qualified != null && qualified.toString().startsWith(ANNOTATIONS_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds declarations the element model cannot reach: any anonymous class (empty simple name),
     * and any named class declared while inside a method body. Named member classes at nesting
     * depth zero are ordinary elements and are left to the normal processing path.
     */
    private final class BodyScanner extends TreeScanner<Void, Void> {

        private final CompilationUnitTree unit;
        private final boolean imported;
        private int methodDepth;

        BodyScanner(CompilationUnitTree unit, boolean imported) {
            this.unit = unit;
            this.imported = imported;
        }

        @Override
        public Void visitMethod(MethodTree method, Void p) {
            methodDepth++;
            try {
                return super.visitMethod(method, p);
            } finally {
                methodDepth--;
            }
        }

        @Override
        public Void visitClass(ClassTree type, Void p) {
            boolean anonymous = type.getSimpleName().isEmpty();
            if (anonymous || methodDepth > 0) {
                reportEveryGuardrailIn(type);
                return null; // the whole subtree was just reported; do not visit it twice
            }
            return super.visitClass(type, p);
        }

        /** Reports every guardrail annotation anywhere inside an unreachable declaration. */
        private void reportEveryGuardrailIn(ClassTree unreachable) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitAnnotation(AnnotationTree annotation, Void p) {
                    String name = annotation.getAnnotationType().toString();
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    boolean ours = name.startsWith(ANNOTATIONS_PACKAGE + ".")
                        || (imported && SIMPLE_NAMES.contains(simple));
                    if (ours && trees != null) {
                        trees.printMessage(Diagnostic.Kind.WARNING,
                            "VibeTags: @" + simple + " on a local or anonymous declaration is"
                                + " invisible to annotation processing (JSR 269 sees declarations,"
                                + " not method bodies): no guardrail entry is generated and no"
                                + " validation runs — and such an element has no stable qualified"
                                + " name to reference anyway. Hoist the guarded logic to a named"
                                + " member, or move the annotation to the enclosing declaration.",
                            annotation, unit);
                    }
                    return super.visitAnnotation(annotation, p);
                }
            }.scan((Tree) unreachable, null);
        }
    }
}
