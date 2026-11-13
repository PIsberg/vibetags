package se.deversity.vibetags.processor.internal;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 *
 * <p>The same pass answers a second question, for the early exit ({@link SourceDigest}): does any
 * guardrail annotation in this round take a value from outside the round? {@code @AILocked(reason =
 * Reasons.LEDGER)} renders the constant's value, and when {@code Reasons} comes off the classpath
 * or the source path, no byte of any source this round was given changes when that value does. A
 * build that recorded its source digest was then skipped on the next build, and the generated
 * files kept the old text for good. See {@link #valuesFromOutsideTheRound()}.
 */
public final class MethodBodyGuardrailScanner {

    private static final String ANNOTATIONS_PACKAGE = AILocked.class.getPackageName();

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

    /** Warnings this scanner has reported; a build that warned may not be skipped next time (#834). */
    private int reported;

    /** See {@link #valuesFromOutsideTheRound()}. */
    private int outsideValues;

    /** Each registered guardrail annotation by simple name, to read an attribute's declared type. */
    private static final Map<String, Class<? extends Annotation>> BY_SIMPLE_NAME = bySimpleName();

    private static Map<String, Class<? extends Annotation>> bySimpleName() {
        Map<String, Class<? extends Annotation>> types = new HashMap<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            types.put(type.getSimpleName(), type);
        }
        return Map.copyOf(types);
    }

    private MethodBodyGuardrailScanner(@Nullable Trees trees) {
        this.trees = trees;
    }

    /** A scanner for {@code env}, or a no-op one when no compiler exposes the Tree API. */
    public static MethodBodyGuardrailScanner forEnv(ProcessingEnvironment env) {
        return new MethodBodyGuardrailScanner(SourcePositionResolver.treesFor(env));
    }

    /** How many warnings {@link #scanAndWarn} has reported so far. */
    public int warningsReported() {
        return reported;
    }

    /**
     * How many guardrail annotation values {@link #scanAndWarn} found that the round's own sources
     * do not determine; a build that has one may not be skipped next time.
     *
     * <p>A value counts unless it is spelled out where it is used (a literal, an enum constant, a
     * class literal, or those combined), or names a constant that a source file of this round
     * declares with a spelled-out initializer. Everything else is counted, including a constant
     * whose own initializer names another: the rule errs towards a missed exit, never towards a
     * false "unchanged". Always zero without the Tree API, where the question cannot be asked; a
     * compiler that does not expose it keeps the exit it had.
     */
    public int valuesFromOutsideTheRound() {
        return outsideValues;
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
                new BodyScanner(unit, importsGuardrails(unit), roots).scan(path.getLeaf(), null);
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
        private final Set<? extends Element> roots;
        private int methodDepth;
        /** Depth inside statements, whose annotations javac has not attributed yet during processing. */
        private int statementDepth;

        BodyScanner(CompilationUnitTree unit, boolean imported, Set<? extends Element> roots) {
            this.unit = unit;
            this.imported = imported;
            this.roots = roots;
        }

        @Override
        public Void visitBlock(BlockTree block, Void p) {
            statementDepth++;
            try {
                return super.visitBlock(block, null);
            } finally {
                statementDepth--;
            }
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void p) {
            statementDepth++;
            try {
                return super.visitLambdaExpression(lambda, null);
            } finally {
                statementDepth--;
            }
        }

        /**
         * A guardrail annotation on a declaration processing can see: counts each value the round's
         * sources do not determine. Annotations inside a statement are not looked at. They generate
         * nothing, and their arguments are not attributed while processing runs, so asking for an
         * argument's element there would make javac attribute the class early.
         */
        @Override
        public Void visitAnnotation(AnnotationTree annotation, Void p) {
            if (statementDepth == 0 && isGuardrail(annotation)) {
                String name = annotation.getAnnotationType().toString();
                Class<? extends Annotation> type = BY_SIMPLE_NAME.get(name.substring(name.lastIndexOf('.') + 1));
                TreePath annotationPath = null;
                for (ExpressionTree argument : annotation.getArguments()) {
                    ExpressionTree value = argument instanceof AssignmentTree assigned
                        ? assigned.getExpression() : argument;
                    if (spelledOut(value) || isEnumAttribute(type, argument)) {
                        continue;
                    }
                    if (annotationPath == null) {
                        annotationPath = TreePath.getPath(unit, annotation);
                    }
                    if (annotationPath == null
                            || !determinedByTheRound(new TreePath(annotationPath, argument), value)) {
                        outsideValues++;
                    }
                }
            }
            return super.visitAnnotation(annotation, null);
        }

        private boolean isGuardrail(AnnotationTree annotation) {
            String name = annotation.getAnnotationType().toString();
            return name.startsWith(ANNOTATIONS_PACKAGE + ".")
                || (imported && SIMPLE_NAMES.contains(name.substring(name.lastIndexOf('.') + 1)));
        }

        /**
         * Whether the attribute {@code argument} sets is declared as an enum or an enum array: a
         * name there can only be an enum constant, which the source spells out. Read off the
         * annotation type itself, so the commonest name-shaped value
         * ({@code framework = Framework.JUNIT_5}) costs no symbol lookup.
         */
        private boolean isEnumAttribute(@Nullable Class<? extends Annotation> type, ExpressionTree argument) {
            if (type == null) {
                return false;
            }
            String attribute = argument instanceof AssignmentTree assigned
                ? assigned.getVariable().toString() : "value";
            try {
                Class<?> declared = type.getMethod(attribute).getReturnType();
                Class<?> component = declared.isArray() ? declared.getComponentType() : declared;
                return component.isEnum();
            } catch (NoSuchMethodException | RuntimeException unknown) {
                return false;
            }
        }

        /** Whether {@code value} is written out in full: literals, class literals, and operators over them. */
        private boolean spelledOut(@Nullable Tree value) {
            if (value instanceof LiteralTree) {
                return true;
            }
            if (value instanceof ParenthesizedTree parenthesized) {
                return spelledOut(parenthesized.getExpression());
            }
            if (value instanceof UnaryTree unary) {
                return spelledOut(unary.getExpression());
            }
            if (value instanceof BinaryTree binary) {
                return spelledOut(binary.getLeftOperand()) && spelledOut(binary.getRightOperand());
            }
            if (value instanceof ConditionalExpressionTree conditional) {
                return spelledOut(conditional.getCondition()) && spelledOut(conditional.getTrueExpression())
                    && spelledOut(conditional.getFalseExpression());
            }
            if (value instanceof TypeCastTree cast) {
                return spelledOut(cast.getExpression());
            }
            if (value instanceof NewArrayTree array) {
                if (array.getInitializers() == null) {
                    return false;
                }
                for (ExpressionTree element : array.getInitializers()) {
                    if (!spelledOut(element)) {
                        return false;
                    }
                }
                return true;
            }
            return value instanceof MemberSelectTree select && select.getIdentifier().contentEquals("class");
        }

        /**
         * Whether a value that is not spelled out is still fixed by this round's sources: every name
         * in it is an enum constant, or a field a root type of this round declares with a spelled-out
         * initializer. {@code parent} is the path of the tree {@code value} sits directly in.
         */
        private boolean determinedByTheRound(TreePath parent, Tree value) {
            if (spelledOut(value)) {
                return true;
            }
            // Trees compare by identity; equals() is that comparison, spelled the way PMD accepts.
            TreePath here = parent.getLeaf().equals(value) ? parent : new TreePath(parent, value);
            if (value instanceof ParenthesizedTree parenthesized) {
                return determinedByTheRound(here, parenthesized.getExpression());
            }
            if (value instanceof UnaryTree unary) {
                return determinedByTheRound(here, unary.getExpression());
            }
            if (value instanceof TypeCastTree cast) {
                return determinedByTheRound(here, cast.getExpression());
            }
            if (value instanceof BinaryTree binary) {
                return determinedByTheRound(here, binary.getLeftOperand())
                    && determinedByTheRound(here, binary.getRightOperand());
            }
            if (value instanceof ConditionalExpressionTree conditional) {
                return determinedByTheRound(here, conditional.getCondition())
                    && determinedByTheRound(here, conditional.getTrueExpression())
                    && determinedByTheRound(here, conditional.getFalseExpression());
            }
            if (value instanceof NewArrayTree array && array.getInitializers() != null) {
                for (ExpressionTree element : array.getInitializers()) {
                    if (!determinedByTheRound(here, element)) {
                        return false;
                    }
                }
                return true;
            }
            Trees api = trees;
            if (api == null || !(value instanceof IdentifierTree || value instanceof MemberSelectTree)) {
                return false;
            }
            Element named = api.getElement(here);
            if (named == null) {
                return false;
            }
            if (named.getKind() == ElementKind.ENUM_CONSTANT) {
                return true;
            }
            if (named.getKind() != ElementKind.FIELD || !roots.contains(outermostTypeOf(named))) {
                return false;
            }
            return api.getTree(named) instanceof VariableTree declared && spelledOut(declared.getInitializer());
        }

        /** The top-level type {@code element} is declared in, or {@code null} when it has none. */
        private @Nullable Element outermostTypeOf(Element element) {
            Element current = element;
            while (current != null && current.getKind() != ElementKind.PACKAGE) {
                Element enclosing = current.getEnclosingElement();
                if (enclosing == null || enclosing.getKind() == ElementKind.PACKAGE) {
                    return current;
                }
                current = enclosing;
            }
            return null;
        }

        @Override
        public Void visitMethod(MethodTree method, Void p) {
            methodDepth++;
            try {
                return super.visitMethod(method, null);
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
            return super.visitClass(type, null);
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
                        reported++;
                        trees.printMessage(Diagnostic.Kind.WARNING,
                            "VibeTags: @" + simple + " on a local or anonymous declaration is"
                                + " invisible to annotation processing (JSR 269 sees declarations,"
                                + " not method bodies): no guardrail entry is generated and no"
                                + " validation runs — and such an element has no stable qualified"
                                + " name to reference anyway. Hoist the guarded logic to a named"
                                + " member, or move the annotation to the enclosing declaration.",
                            annotation, unit);
                    }
                    return super.visitAnnotation(annotation, null);
                }
            }.scan((Tree) unreachable, null);
        }
    }
}
