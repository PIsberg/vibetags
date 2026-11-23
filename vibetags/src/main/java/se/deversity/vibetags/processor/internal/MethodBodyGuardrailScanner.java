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
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

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
 * files kept the old text for good. See {@link #valuesFromOutsideTheRound()}. Without the Tree API
 * the same question is answered from the round's source text instead (#1062).
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

    /** Whether the text fallback counts values without the Tree API; false in a no-op scanner. */
    private final boolean countsWithoutTrees;

    /** The source text the fallback has read, by file, so each file is read once per build. */
    private final Map<Path, String> sourceTexts = new HashMap<>();

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

    private MethodBodyGuardrailScanner(@Nullable Trees trees, boolean countsWithoutTrees) {
        this.trees = trees;
        this.countsWithoutTrees = countsWithoutTrees;
    }

    /**
     * A scanner for {@code env}. Without the Tree API it warns about nothing, and answers
     * {@link #valuesFromOutsideTheRound()} from the source text.
     */
    public static MethodBodyGuardrailScanner forEnv(ProcessingEnvironment env) {
        return new MethodBodyGuardrailScanner(SourcePositionResolver.treesFor(env), true);
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
     * false "unchanged". Without the Tree API (ECJ, a build tool's decorator no unwrap sees through)
     * the argument trees cannot be read, so a value counts unless the round's source text spells it
     * out: see {@link #countValuesWithoutTrees}. Before #1062 nothing was counted there, and a
     * dependency constant's old value was kept for good.
     */
    public int valuesFromOutsideTheRound() {
        return outsideValues;
    }

    /** A scanner that never reports and counts nothing, for tests. */
    public static MethodBodyGuardrailScanner noop() {
        return new MethodBodyGuardrailScanner(null, false);
    }

    /**
     * Scans this round's root elements for guardrail annotations on local or anonymous
     * declarations, reporting each as a WARNING anchored at the annotation itself.
     *
     * @param sources the round's element-to-file mapping, which the fallback without the Tree API
     *                reads files through, so no element is mapped to its file twice (#857)
     */
    public void scanAndWarn(RoundEnvironment roundEnv, RoundSources sources) {
        if (trees == null) {
            Elements utils = sources.elements();
            if (countsWithoutTrees && utils != null) {
                countValuesWithoutTrees(roundEnv, sources, utils);
            }
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

    /**
     * {@link #valuesFromOutsideTheRound()} without the Tree API (#1062). Each value a guardrail
     * annotation sets explicitly counts unless the round's source text spells it out: a string as
     * its Java literal, a number or boolean as a token, in the annotated element's own file or
     * another file of the round. Enum constants and class literals are names the source carries, so
     * they never count. A value written any other way (a constant, a concatenation, a text block)
     * counts, and so does one that cannot be checked because a file cannot be read: the rule errs
     * towards a missed exit, never towards a false "unchanged". Defaults are not looked at; they
     * come from the annotation type, not from the round.
     */
    private void countValuesWithoutTrees(RoundEnvironment roundEnv, RoundSources sources, Elements utils) {
        try {
            Set<? extends Element> roots = roundEnv.getRootElements();
            for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
                TypeElement annotation = utils.getTypeElement(type.getCanonicalName());
                if (annotation == null) {
                    countUseSiteValues(roundEnv, type, roots, sources);
                    continue;
                }
                for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    countExplicitValues(element, annotation, roots, sources);
                }
            }
        } catch (RuntimeException unanswerable) {
            outsideValues++; // a round this cannot read is not one to vouch for
        }
    }

    /** Adds each value {@code element}'s {@code annotation} sets that the round's text does not spell. */
    private void countExplicitValues(Element element, TypeElement annotation, Set<? extends Element> roots,
                                     RoundSources sources) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (!annotation.equals(mirror.getAnnotationType().asElement())) {
                continue;
            }
            for (AnnotationValue value : mirror.getElementValues().values()) {
                if (!spelledOutInTheRound(value.getValue(), element, roots, sources)) {
                    outsideValues++;
                }
            }
        }
    }

    /**
     * The same count for an element model whose annotation types cannot be looked up by name and
     * whose elements carry no mirrors ({@code vibetags-ksp}, #1121): the values come from
     * {@link UseSiteAnnotationValues}. An annotated element that offers neither counts once, since
     * nothing says its values were spelled out.
     */
    private void countUseSiteValues(RoundEnvironment roundEnv, Class<? extends Annotation> type,
                                    Set<? extends Element> roots, RoundSources sources) {
        for (Element element : roundEnv.getElementsAnnotatedWith(type)) {
            Map<String, Object> values = element instanceof UseSiteAnnotationValues site
                ? site.useSiteValues(type.getName()) : null;
            if (values == null) {
                outsideValues++;
                continue;
            }
            for (Object value : values.values()) {
                if (!spelledOutInTheRound(value, element, roots, sources)) {
                    outsideValues++;
                }
            }
        }
    }

    private boolean spelledOutInTheRound(@Nullable Object value, Element element, Set<? extends Element> roots,
                                         RoundSources sources) {
        if (value instanceof VariableElement || value instanceof TypeMirror || value == UseSiteAnnotationValues.NAMED) {
            return true; // an enum constant or a class literal: the source names it
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Object inner = item instanceof AnnotationValue av ? av.getValue() : item;
                if (!spelledOutInTheRound(inner, element, roots, sources)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof String text) {
            String literal = javaLiteral(text);
            return appearsInTheRound(source -> containsText(source, literal), element, roots, sources);
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            String token = String.valueOf(value);
            return appearsInTheRound(source -> containsToken(source, token), element, roots, sources);
        }
        return false;
    }

    /**
     * Whether {@code found} accepts the text of the element's own file or of any other file of the
     * round. A file that cannot be read is text nobody can find anything in.
     */
    private boolean appearsInTheRound(Predicate<String> found, Element element, Set<? extends Element> roots,
                                      RoundSources sources) {
        if (readable(textOf(topLevel(element), sources), found)) {
            return true;
        }
        for (Element root : roots) {
            if (readable(textOf(root, sources), found)) {
                return true;
            }
        }
        return false;
    }

    /** The outermost type around {@code element}, which names its source file; itself when top-level. */
    static Element topLevel(Element element) {
        Element current = element;
        while (isNested(current)) {
            current = Objects.requireNonNull(current.getEnclosingElement(), "isNested checked it");
        }
        return current;
    }

    /**
     * Whether {@code element} is declared inside a type, a member or an executable, rather than
     * directly in a package or module: the elements {@link #topLevel} climbs out of.
     */
    private static boolean isNested(Element element) {
        Element enclosing = element.getEnclosingElement();
        if (enclosing == null) {
            return false;
        }
        ElementKind kind = enclosing.getKind();
        return kind.isClass() || kind.isInterface() || kind.isExecutable() || kind.isField();
    }

    /**
     * The text of the file a root element was declared in, or {@code null} for an in-memory source
     * or an unreadable file. Read as ISO-8859-1, which cannot fail on any byte: every token looked
     * for is matched against its own encoding of the same characters only where they are ASCII, and
     * a non-ASCII value that does not match counts, which is the safe direction.
     */
    private @Nullable String textOf(Element element, RoundSources sources) {
        Path file = sources.fileOf(element);
        if (file == null) {
            return null;
        }
        String cached = sourceTexts.get(file);
        if (cached != null) {
            return cached;
        }
        try {
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            sourceTexts.put(file, text);
            return text;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean readable(@Nullable String text, Predicate<String> found) {
        return text != null && found.test(text);
    }

    /** Whether {@code text} contains {@code token} anywhere: how a string's Java literal is found. */
    static boolean containsText(@Nullable String text, String token) {
        return text != null && !token.isEmpty() && text.contains(token);
    }

    /**
     * Whether {@code text} contains {@code token} where it is not part of a longer identifier or
     * number ({@code 9} is not found in {@code 90}): how a number or boolean is found.
     */
    static boolean containsToken(@Nullable String text, String token) {
        if (text == null || token.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            int end = at + token.length();
            boolean before = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            boolean after = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (before && after) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * {@code value} as the Java string literal that spells it most plainly: quotes, with a backslash
     * before a quote or a backslash and the usual escapes for newline, tab and carriage return. A
     * value written another way (a Unicode escape, a text block) does not match, and so counts.
     */
    static String javaLiteral(String value) {
        StringBuilder literal = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\t' -> literal.append("\\t");
                case '\r' -> literal.append("\\r");
                default -> literal.append(c);
            }
        }
        return literal.append('"').toString();
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

    /** A test applied to each leaf of a value; see {@code BodyScanner#everyOperand}. */
    @FunctionalInterface
    private interface Leaf {
        boolean accepts(@Nullable TreePath path, Tree leaf);
    }

    /**
     * Finds declarations the element model cannot reach: any anonymous class (empty simple name),
     * and any named class declared inside a block or a lambda: a method body, an initializer, or a
     * lambda in a field initializer. Named member classes at nesting
     * depth zero are ordinary elements and are left to the normal processing path.
     */
    private final class BodyScanner extends TreeScanner<Void, Void> {

        private final CompilationUnitTree unit;
        private final boolean imported;
        private final Set<? extends Element> roots;
        /**
         * Depth inside statements (blocks and lambdas): a class declared there is local, and
         * annotations there have not been attributed by javac yet during processing.
         */
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
                countValuesFromOutsideTheRound(annotation);
            }
            return super.visitAnnotation(annotation, null);
        }

        /** Adds each argument of {@code annotation} that this round's sources do not determine. */
        private void countValuesFromOutsideTheRound(AnnotationTree annotation) {
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
        private static boolean spelledOut(@Nullable Tree value) {
            return value != null && everyOperand(null, value, (path, leaf) -> isWrittenOut(leaf));
        }

        /**
         * Whether a value that is not spelled out is still fixed by this round's sources: every name
         * in it is an enum constant, or a field a root type of this round declares with a spelled-out
         * initializer. {@code parent} is the path of the tree {@code value} sits directly in.
         */
        private boolean determinedByTheRound(TreePath parent, Tree value) {
            return everyOperand(parent, value, (path, leaf) -> isWrittenOut(leaf) || namesARoundConstant(path, leaf));
        }

        /**
         * Whether {@code leaf} accepts every leaf of {@code value}, looking through what only combines
         * other values ({@link #operandsOf}). Each leaf is offered with its own path when
         * {@code parent} is the path of the tree {@code value} sits directly in, and with none when
         * {@code parent} is {@code null}.
         */
        private static boolean everyOperand(@Nullable TreePath parent, Tree value, Leaf leaf) {
            // Trees compare by identity; equals() is that comparison, spelled the way PMD accepts.
            TreePath here = (parent == null || parent.getLeaf().equals(value)) ? parent : new TreePath(parent, value);
            List<? extends Tree> operands = operandsOf(value);
            if (operands == null) {
                return leaf.accepts(here, value);
            }
            for (Tree operand : operands) {
                if (!everyOperand(here, operand, leaf)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The values {@code value} combines without adding one of its own: the operand of
         * parentheses, a unary operator or a cast, both of a binary operator, all three of a
         * conditional, every element of an array initializer. {@code null} for a leaf, which
         * includes an array created by size, since it has no initializer to look through.
         */
        private static @Nullable List<? extends Tree> operandsOf(Tree value) {
            return switch (value) {
                case ParenthesizedTree parenthesized -> List.of(parenthesized.getExpression());
                case UnaryTree unary -> List.of(unary.getExpression());
                case TypeCastTree cast -> List.of(cast.getExpression());
                case BinaryTree binary -> List.of(binary.getLeftOperand(), binary.getRightOperand());
                case ConditionalExpressionTree conditional -> List.of(conditional.getCondition(),
                    conditional.getTrueExpression(), conditional.getFalseExpression());
                case NewArrayTree array -> array.getInitializers();
                default -> null;
            };
        }

        /** A literal or a class literal: a leaf whose value the source itself spells. */
        private static boolean isWrittenOut(Tree leaf) {
            return leaf instanceof LiteralTree
                || (leaf instanceof MemberSelectTree select && select.getIdentifier().contentEquals("class"));
        }

        /**
         * Whether {@code leaf}, at {@code path}, names an enum constant or a field a root type of
         * this round declares with a spelled-out initializer.
         */
        private boolean namesARoundConstant(@Nullable TreePath path, Tree leaf) {
            Trees api = trees;
            if (api == null || path == null || !(leaf instanceof IdentifierTree || leaf instanceof MemberSelectTree)) {
                return false;
            }
            Element named = api.getElement(path);
            if (named == null) {
                return false;
            }
            return named.getKind() == ElementKind.ENUM_CONSTANT
                || (named.getKind() == ElementKind.FIELD
                    && roots.contains(outermostTypeOf(named))
                    && api.getTree(named) instanceof VariableTree declared
                    && spelledOut(declared.getInitializer()));
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
        public Void visitClass(ClassTree type, Void p) {
            // A named class inside a block or a lambda is local, whether that block is a method body,
            // an initializer or a lambda in a field initializer: processing reaches none of them (#1176).
            boolean anonymous = type.getSimpleName().isEmpty();
            if (anonymous || statementDepth > 0) {
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
