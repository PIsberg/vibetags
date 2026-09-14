package se.deversity.vibetags.cli;

import java.lang.annotation.ElementType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code @AI*} guardrails on Kotlin declarations that kapt leaves out of its Java stubs.
 *
 * <p>A function that takes a value class gets a mangled JVM name ({@code balanceFor-oKSF6Yo}), and
 * kapt omits every such declaration from the stub it hands to annotation processors, so a guardrail
 * on it or on one of its parameters generates nothing and logs nothing
 * (<a href="https://github.com/PIsberg/vibetags/issues/681">#681</a>). The processor cannot warn:
 * the declaration is not in what it sees, and the stub's {@code @kotlin.Metadata} does not record
 * {@code SOURCE}-retention annotations. Doctor can read the {@code .kt} files, so it reports them
 * instead (<a href="https://github.com/PIsberg/vibetags/issues/688">#688</a>).
 *
 * <p>Every rule below reports a shape that a real kapt build on Kotlin 2.4.10 showed to be lost, and
 * every shape that build showed to be kept, or never built, stays silent
 * (<a href="https://github.com/PIsberg/vibetags/issues/692">#692</a>; the table is in
 * {@code docs/JVM-LANGUAGES.md}):
 * <ul>
 *   <li>Top level: a function with a value class as a parameter or extension receiver, and a
 *       property's {@code @set:} or {@code @setparam:} guardrail. A top-level return type does not
 *       mangle, so {@code fun makeId(): AccountId} and a top-level {@code @get:} are kept.</li>
 *   <li>Members of a class, object, companion or interface: a value class as a parameter, receiver
 *       or return type, suspend included; a property's {@code @get:}, {@code @set:} or
 *       {@code @setparam:}; a constructor (primary or secondary) taking one.</li>
 *   <li>Directly inside a value class: every function, {@code @get:} property and secondary
 *       constructor, whatever its signature, because they compile to static {@code -impl}
 *       functions.</li>
 *   <li>{@code @JvmName} (or {@code @get:}/{@code @set:JvmName}) and {@code @JvmExposeBoxed} on the
 *       declaration keep it. {@code @JvmExposeBoxed} on a class, or {@code -Xjvm-expose-boxed} on
 *       the module ({@link Source#exposeBoxedModule()}), keeps what they expose; what they do not
 *       expose is still reported: suspend functions, open, abstract and interface members, a value
 *       class's secondary constructors, and members of a class nested inside the annotated one.</li>
 * </ul>
 *
 * <p>This is a heuristic over source text, not a compiler, so a doubt produces a miss rather than a
 * false finding. A type counts only when it resolves, through the file's package and imports, to a
 * value class declared in the scanned sources, found in a class file on doctor's
 * {@code --classpath} ({@link JvmInlineClasses}), or one of {@link #STDLIB_VALUE_CLASSES}. Only the
 * head of a type counts ({@code List<AccountId>} is not mangled), {@code kotlin.Result} is never a
 * hit, and declarations inside function bodies, anonymous objects and extension properties are not
 * reported.
 */
final class KotlinValueClassScan {

    /**
     * Standard-library value classes. The unsigned types: "Unsigned numbers are implemented as
     * inline classes" (kotlinlang.org/docs/unsigned-integer-types.html). {@code Duration} is
     * declared {@code @JvmInline value class Duration}
     * (kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-duration/), and a member function returning
     * it was measured absent from kapt's stub in #689 and #692. {@code kotlin.Result} is a value class
     * too and is left out on purpose: it is not mangled.
     */
    static final Set<String> STDLIB_VALUE_CLASSES = Set.of(
        "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong", "kotlin.time.Duration");

    private static final String ANNOTATIONS_PACKAGE = "se.deversity.vibetags.annotations";
    private static final String EXPOSE_BOXED = "JvmExposeBoxed";
    private static final String JVM_NAME = "JvmName";
    private static final String DOCS = "docs/JVM-LANGUAGES.md, Kotlin";
    private static final String DROPPED = "; the guardrail is dropped before any processor sees it. ";
    private static final String ENCLOSING_TYPE = "Put the guardrail on the enclosing type";
    private static final String ON_VALUE_CLASS = "Put the guardrail on the value class itself";

    /** Words that may sit between a declaration's annotations and its keyword. */
    private static final Set<String> MODIFIERS = Set.of(
        "public", "private", "protected", "internal", "override", "open", "final", "abstract",
        "suspend", "inline", "operator", "infix", "tailrec", "external", "actual", "expect",
        "value", "data", "enum", "sealed", "annotation", "companion", "inner", "const", "lateinit",
        "noinline", "crossinline", "vararg");

    private static final Set<String> VISIBILITY = Set.of("public", "private", "protected", "internal");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern IMPORT =
        Pattern.compile("(?m)^\\s*import\\s+([\\w.]+?)(\\.\\*)?(?:\\s+as\\s+(\\w+))?\\s*;?\\s*$");
    private static final Pattern DECLARATION = Pattern.compile(
        "(?<![:.\\w])(?:(value|inline)\\s+)?(?:class|interface|object|typealias)\\s+(\\w+)");
    private static final Pattern TYPE_HEAD = Pattern.compile("^[\\w.]+");

    private static final Set<String> FUNCTION_GUARDRAILS =
        Set.copyOf(DoctorCommand.guardrailsTargeting(ElementType.METHOD));
    private static final Set<String> PARAMETER_GUARDRAILS =
        Set.copyOf(DoctorCommand.guardrailsTargeting(ElementType.PARAMETER));
    private static final Set<String> CONSTRUCTOR_GUARDRAILS =
        Set.copyOf(DoctorCommand.guardrailsTargeting(ElementType.CONSTRUCTOR));

    /**
     * A Kotlin source file as doctor read it: the name to print, its text, and whether a build file
     * above it passes {@code -Xjvm-expose-boxed}.
     */
    record Source(String name, String text, boolean exposeBoxedModule) {
    }

    /** What the scan found: one finding per declaration that loses guardrails. */
    record Report(List<String> findings, int declaredValueClasses) {
    }

    /** One parsed file: its comment- and string-blanked code plus the names it can resolve. */
    private record FileContext(Source source, String code, String pkg,
                               Map<String, String> explicitImports, List<String> starImports) {
    }

    /** The value classes a scan knows: declared in the sources plus the standard library's. */
    private record Known(Set<String> valueClasses, Set<String> declaredTypes) {
    }

    /** An annotation as written: optional use-site target, optional qualifier, simple name. */
    private record Ann(String written) {
        private String withoutTarget() {
            return written.substring(written.indexOf(':') + 1);
        }

        String simpleName() {
            String name = withoutTarget();
            return name.substring(name.lastIndexOf('.') + 1);
        }

        String qualifier() {
            String name = withoutTarget();
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(0, dot);
        }

        String useSiteTarget() {
            int colon = written.indexOf(':');
            return colon < 0 ? "" : written.substring(0, colon);
        }
    }

    /** The kind of body a declaration sits directly in. */
    private enum Body { TOP, CLASS, INTERFACE, VALUE_CLASS, OTHER }

    /**
     * One body on the scope stack: its kind, the name of the class that opened it, whether that
     * class carries {@code @JvmExposeBoxed}, and whether it is an enum.
     */
    private record Scope(Body body, String name, boolean exposed, boolean enumClass) {
    }

    private static final Scope OTHER_SCOPE = new Scope(Body.OTHER, "", false, false);

    /** A declaration: its file, the body it sits in, what was written before it, and where it starts. */
    private record Decl(FileContext file, Scope scope, List<Ann> annotations, Set<String> modifiers,
                        int offset) {

        /** Whether an annotation with this simple name and use-site target ("" for none) is present. */
        boolean annotated(String simpleName, String target) {
            return annotations.stream()
                .anyMatch(a -> simpleName.equals(a.simpleName()) && target.equals(a.useSiteTarget()));
        }

        /** {@code -Xjvm-expose-boxed} on the module, or {@code @JvmExposeBoxed} on the enclosing class. */
        boolean exposedByContext() {
            return file.source().exposeBoxedModule() || scope.exposed();
        }

        /**
         * What keeps this member out of a boxed variant, or empty. The compiler: "'@JvmExposeBoxed'
         * cannot expose functions which are open or abstract, or member of an interface"; suspend
         * functions were measured unexposed too (#692).
         */
        String unexposable() {
            if (modifiers.contains("suspend")) {
                return "a suspend function";
            }
            if (scope.body() == Body.INTERFACE) {
                return "an interface member";
            }
            if (modifiers.contains("open")) {
                return "open";
            }
            return modifiers.contains("abstract") ? "abstract" : "";
        }

        /** Open, abstract, override and interface members cannot take {@code @JvmName}. */
        boolean overridable() {
            return scope.body() == Body.INTERFACE || modifiers.contains("open")
                || modifiers.contains("abstract") || modifiers.contains("override");
        }
    }

    /** A parameter as written: annotations, modifiers, {@code val}/{@code var} or empty, name and type. */
    private record Param(List<Ann> annotations, Set<String> modifiers, String keyword, String name,
                         String type, int keywordOffset) {
    }

    /** Where scanning resumes after a class header, and the scope its body would open. */
    private record Header(int resume, Scope body) {
    }

    private KotlinValueClassScan() {
    }

    /**
     * Scans {@code sources}, resolving types against the value classes they declare, the standard
     * library's, and {@code classpathValueClasses} read from compiled dependencies (#691). A name the
     * sources declare as an ordinary type wins over a class file saying otherwise.
     */
    static Report scan(List<Source> sources, Set<String> classpathValueClasses) {
        List<FileContext> files = new ArrayList<>();
        Set<String> valueClasses = new HashSet<>();
        Set<String> otherTypes = new HashSet<>();
        for (Source source : sources) {
            FileContext file = parse(source);
            files.add(file);
            Matcher m = DECLARATION.matcher(file.code());
            while (m.find()) {
                (m.group(1) == null ? otherTypes : valueClasses).add(qualify(file.pkg(), m.group(2)));
            }
        }
        // A name declared both ways (a nested value class next to a same-named top-level class)
        // cannot be told apart by text, so it is dropped rather than guessed.
        valueClasses.removeAll(otherTypes);
        int declared = valueClasses.size();
        Set<String> fromClasspath = new HashSet<>(classpathValueClasses);
        fromClasspath.removeAll(otherTypes);
        fromClasspath.remove("kotlin.Result");
        valueClasses.addAll(fromClasspath);
        Set<String> declaredTypes = new HashSet<>(otherTypes);
        declaredTypes.addAll(valueClasses);
        valueClasses.addAll(STDLIB_VALUE_CLASSES);
        Known known = new Known(valueClasses, declaredTypes);

        List<String> findings = new ArrayList<>();
        for (FileContext file : files) {
            scanDeclarations(file, known, findings);
        }
        return new Report(findings, declared);
    }

    private static FileContext parse(Source source) {
        String code = blankCommentsAndStrings(source.text());
        Matcher pkg = PACKAGE.matcher(code);
        String packageName = pkg.find() ? pkg.group(1) : "";
        Map<String, String> explicit = new HashMap<>();
        List<String> star = new ArrayList<>();
        Matcher imp = IMPORT.matcher(code);
        while (imp.find()) {
            String path = imp.group(1);
            String alias = imp.group(3);
            if (imp.group(2) != null) {
                star.add(path);
            } else if (alias == null) {
                explicit.put(path.substring(path.lastIndexOf('.') + 1), path);
            } else {
                explicit.put(alias, path);
            }
        }
        return new FileContext(source, code, packageName, explicit, star);
    }

    /**
     * Walks the file collecting annotations and modifiers until a declaration keyword binds them,
     * keeping a stack of the bodies it is in. Anything else discards what was collected, so an
     * annotation never travels past the declaration it belongs to. A class header arms the scope its
     * opening brace pushes; any other brace opens a body whose declarations are not reported.
     */
    private static void scanDeclarations(FileContext file, Known known, List<String> findings) {
        String code = file.code();
        Deque<Scope> scopes = new ArrayDeque<>();
        scopes.push(new Scope(Body.TOP, "", false, false));
        List<Ann> pending = new ArrayList<>();
        Set<String> modifiers = new HashSet<>();
        Scope armed = null;
        int armedAt = 0;
        int parens = 0;
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '@') {
                pending.add(new Ann(code.substring(i + 1, nameEnd(code, i + 1))));
                i = annotationEnd(code, i + 1);
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(code, i);
                String word = code.substring(i, end);
                boolean qualified = i > 0 && (code.charAt(i - 1) == '.' || code.charAt(i - 1) == ':');
                boolean funInterface = "fun".equals(word) && "interface".equals(wordAt(code, end));
                if (!qualified && !funInterface && isDeclarationKeyword(word)) {
                    if (parens == armedAt) {
                        armed = null;
                    }
                    Decl decl = new Decl(file, scopes.element(), List.copyOf(pending), Set.copyOf(modifiers), i);
                    pending.clear();
                    modifiers.clear();
                    if ("fun".equals(word)) {
                        i = function(decl, end, known, findings);
                    } else if ("val".equals(word) || "var".equals(word)) {
                        i = property(decl, end, known, findings);
                    } else if ("constructor".equals(word)) {
                        i = secondaryConstructor(decl, end, known, findings);
                    } else {
                        Header header = classHeader(decl, word, end, known, findings);
                        i = header.resume();
                        armed = header.body();
                        armedAt = parens;
                    }
                    continue;
                }
                if (MODIFIERS.contains(word) || funInterface) {
                    modifiers.add(word);
                } else {
                    pending.clear();
                    modifiers.clear();
                }
                i = end;
                continue;
            }
            if (c == '{') {
                scopes.push(armed != null && parens == armedAt ? armed : OTHER_SCOPE);
                armed = null;
            } else if (c == '}') {
                if (scopes.size() > 1) {
                    scopes.pop();
                }
                armed = null;
            } else if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            }
            if (!Character.isWhitespace(c)) {
                pending.clear();
                modifiers.clear();
            }
            i++;
        }
    }

    private static boolean isDeclarationKeyword(String word) {
        return switch (word) {
            case "fun", "val", "var", "constructor", "class", "interface", "object" -> true;
            default -> false;
        };
    }

    /**
     * Parses the class, interface or object header whose keyword ends at {@code from}, reports its
     * primary constructor, and returns where scanning resumes and the scope its body would open.
     */
    private static Header classHeader(Decl decl, String keyword, int from, Known known,
                                      List<String> findings) {
        String code = decl.file().code();
        Set<String> mods = decl.modifiers();
        int j = skipWhitespace(code, from);
        boolean named = j < code.length() && Character.isJavaIdentifierStart(code.charAt(j));
        String name = named ? code.substring(j, identifierEnd(code, j)) : "";
        Body body;
        if (decl.scope().body() == Body.OTHER) {
            body = Body.OTHER;   // a local class: nothing in one was measured
        } else if ("interface".equals(keyword)) {
            body = Body.INTERFACE;
        } else if ("object".equals(keyword)) {
            body = named || mods.contains("companion") ? Body.CLASS : Body.OTHER;
        } else {
            body = mods.contains("value") || mods.contains("inline") ? Body.VALUE_CLASS : Body.CLASS;
        }
        Scope scope = new Scope(body, name, decl.annotated(EXPOSE_BOXED, ""), mods.contains("enum"));
        if (!named) {
            return new Header(from, scope);
        }
        int k = skipWhitespace(code, identifierEnd(code, j));
        if (k < code.length() && code.charAt(k) == '<') {
            k = skipWhitespace(code, closing(code, k, '<', '>') + 1);
        }
        List<Ann> ctorAnnotations = new ArrayList<>();
        Set<String> ctorModifiers = new HashSet<>();
        int ctorAt = j;
        int p = k;
        while (p < code.length()) {
            if (code.charAt(p) == '@') {
                ctorAnnotations.add(new Ann(code.substring(p + 1, nameEnd(code, p + 1))));
                p = skipWhitespace(code, annotationEnd(code, p + 1));
                continue;
            }
            String word = wordAt(code, p);
            if ("constructor".equals(word)) {
                ctorAt = p;
                p = skipWhitespace(code, p + word.length());
                break;
            }
            if (!VISIBILITY.contains(word)) {
                break;
            }
            ctorModifiers.add(word);
            p = skipWhitespace(code, p + word.length());
        }
        if (p >= code.length() || code.charAt(p) != '(') {
            return new Header(k, scope);   // no primary constructor: resume before any annotation seen
        }
        int close = closing(code, p, '(', ')');
        if (close >= code.length()) {
            return new Header(k, scope);
        }
        if (body == Body.CLASS && "class".equals(keyword) && !scope.enumClass()) {
            Decl ctor = new Decl(decl.file(), scope, List.copyOf(ctorAnnotations), Set.copyOf(ctorModifiers), ctorAt);
            constructor(ctor, p, close, true, known, findings);
        }
        return new Header(close + 1, scope);
    }

    /**
     * Parses the function whose {@code fun} keyword ends at {@code from}, records a finding when it
     * loses guardrails, and returns where scanning resumes.
     */
    private static int function(Decl decl, int from, Known known, List<String> findings) {
        FileContext file = decl.file();
        String code = file.code();
        int j = skipWhitespace(code, from);
        if (j < code.length() && code.charAt(j) == '<') {
            j = skipWhitespace(code, closing(code, j, '<', '>') + 1);
        }
        int open = headerParen(code, j);
        if (open < 0) {
            return from;
        }
        String header = code.substring(j, open).strip();
        int dot = lastTopLevelDot(header);
        String name = header.substring(dot + 1).strip();
        int close = closing(code, open, '(', ')');
        if (name.isEmpty() || close >= code.length()) {
            return from;
        }
        boolean jvmName = decl.annotations().stream()
            .anyMatch(a -> JVM_NAME.equals(a.simpleName()) && !"file".equals(a.useSiteTarget()));
        if (decl.scope().body() == Body.OTHER || jvmName || decl.annotated(EXPOSE_BOXED, "")) {
            return close + 1;
        }

        List<String> lost = new ArrayList<>();
        for (Ann a : decl.annotations()) {
            if (a.useSiteTarget().isEmpty() && isGuardrail(a, FUNCTION_GUARDRAILS)) {
                lost.add("@" + a.simpleName());
            }
        }
        Set<String> takes = new TreeSet<>();
        if (dot >= 0) {
            typeHead(header.substring(0, dot)).flatMap(head -> resolve(file, head, known)).ifPresent(takes::add);
        }
        for (int[] span : splitTopLevel(code, open + 1, close)) {
            parseParameter(code, span).ifPresent(p -> functionParameter(file, p, known, lost, takes));
        }
        Set<String> returns = new TreeSet<>();
        int k = skipWhitespace(code, close + 1);
        if (k < code.length() && code.charAt(k) == ':') {
            typeHead(code.substring(k + 1, Math.min(code.length(), k + 1 + 512)))
                .flatMap(head -> resolve(file, head, known))
                .ifPresent(returns::add);
        }
        if (!lost.isEmpty()) {
            functionLoss(decl, name, takes, returns)
                .ifPresent(why -> findings.add(finding(decl, lost, "fun " + name, why)));
        }
        return close + 1;
    }

    /** Why a guardrailed function is lost, as the rest of its finding, or empty when it is kept. */
    private static Optional<String> functionLoss(Decl decl, String name, Set<String> takes, Set<String> returns) {
        Body body = decl.scope().body();
        if (body == Body.VALUE_CLASS) {
            // Members of a value class compile to static describe-impl functions. An override also
            // keeps an instance bridge (toString()), which was not measured, so it stays silent.
            boolean silent = decl.exposedByContext() || decl.modifiers().contains("suspend")
                || decl.modifiers().contains("override");
            return silent ? Optional.empty() : Optional.of("a value class's members compile to static "
                + "functions with mangled names (" + name + "-impl), and kapt leaves them out of the Java "
                + "stubs" + DROPPED + ON_VALUE_CLASS);
        }
        Set<String> mangledBy = new TreeSet<>(takes);
        if (body != Body.TOP) {
            mangledBy.addAll(returns);   // a top-level return type does not mangle (#692)
        }
        if (mangledBy.isEmpty()) {
            return Optional.empty();
        }
        String uses = "its signature uses value class " + String.join(", ", mangledBy);
        if (decl.exposedByContext()) {
            return unexposed(decl, uses);
        }
        String remedy = decl.overridable()
            ? ENCLOSING_TYPE
            : "Add @JvmName(\"" + name + "\") to the function to keep it";
        return Optional.of(uses + ", so its JVM name is mangled and kapt leaves it out of the Java stubs"
            + DROPPED + remedy);
    }

    /** The finding for a declaration a boxed variant would have kept, had it been exposable. */
    private static Optional<String> unexposed(Decl decl, String uses) {
        String unexposable = decl.unexposable();
        return unexposable.isEmpty() ? Optional.empty() : Optional.of(uses + " and it is " + unexposable
            + ", which -Xjvm-expose-boxed and @JvmExposeBoxed do not expose, so kapt leaves it out of the "
            + "Java stubs" + DROPPED + ENCLOSING_TYPE);
    }

    /** One function parameter: its guardrails join {@code lost}, and its type may join {@code takes}. */
    private static void functionParameter(FileContext file, Param p, Known known, List<String> lost,
                                          Set<String> takes) {
        // A vararg of a value class does not compile ("Prohibited vararg parameter type", #692).
        if (!p.modifiers().contains("vararg")) {
            typeHead(p.type()).flatMap(head -> resolve(file, head, known)).ifPresent(takes::add);
        }
        for (Ann a : p.annotations()) {
            String target = a.useSiteTarget();
            if ((target.isEmpty() || "param".equals(target)) && isGuardrail(a, PARAMETER_GUARDRAILS)) {
                lost.add("@" + a.simpleName() + " (parameter " + p.name() + ")");
            }
        }
    }

    /** A secondary constructor whose keyword ends at {@code from}; returns where scanning resumes. */
    private static int secondaryConstructor(Decl decl, int from, Known known, List<String> findings) {
        String code = decl.file().code();
        int open = skipWhitespace(code, from);
        if (open >= code.length() || code.charAt(open) != '(') {
            return from;
        }
        int close = closing(code, open, '(', ')');
        if (close >= code.length()) {
            return from;
        }
        Body body = decl.scope().body();
        if ((body == Body.CLASS || body == Body.VALUE_CLASS) && !decl.scope().enumClass()) {
            constructor(decl, open, close, false, known, findings);
        }
        return close + 1;
    }

    /**
     * A constructor whose parameter list spans {@code (open, close)}. A class's constructor taking a
     * value class compiles to a private constructor plus a synthetic public one, and kapt's stub
     * carries neither with its guardrails; a value class's secondary constructor compiles to a static
     * {@code constructor-impl}. A primary constructor's {@code val}/{@code var} parameters are
     * properties, whose accessor guardrails follow the property rules.
     */
    private static void constructor(Decl ctor, int open, int close, boolean primary, Known known,
                                     List<String> findings) {
        FileContext file = ctor.file();
        String code = file.code();
        List<String> lost = new ArrayList<>();
        for (Ann a : ctor.annotations()) {
            if (a.useSiteTarget().isEmpty() && isGuardrail(a, CONSTRUCTOR_GUARDRAILS)) {
                lost.add("@" + a.simpleName());
            }
        }
        Set<String> takes = new TreeSet<>();
        for (int[] span : splitTopLevel(code, open + 1, close)) {
            Optional<Param> parsed = parseParameter(code, span);
            if (parsed.isEmpty()) {
                continue;
            }
            Param p = parsed.get();
            if (p.keyword().isEmpty()) {
                functionParameter(file, p, known, lost, takes);
                continue;
            }
            Optional<String> type = typeHead(p.type()).flatMap(head -> resolve(file, head, known));
            type.ifPresent(takes::add);
            if (primary) {
                Decl property = new Decl(file, ctor.scope(), p.annotations(), p.modifiers(), p.keywordOffset());
                accessorFindings(property, p.name(), type, findings);
            }
        }
        if (lost.isEmpty() || ctor.annotated(EXPOSE_BOXED, "")) {
            return;
        }
        String what = "constructor " + ctor.scope().name();
        if (ctor.scope().body() == Body.VALUE_CLASS) {
            if (!ctor.scope().exposed()) {
                findings.add(finding(ctor, lost, what, "a value class's secondary constructors compile to "
                    + "static constructor-impl functions, and kapt leaves them out of the Java stubs"
                    + DROPPED + ON_VALUE_CLASS));
            }
            return;
        }
        if (!takes.isEmpty() && !ctor.exposedByContext()) {
            findings.add(finding(ctor, lost, what, "its parameters use value class " + String.join(", ", takes)
                + ", so it compiles to a private constructor plus a synthetic one and kapt's stub carries "
                + "neither with its guardrails" + DROPPED
                + "Put the guardrail on the class, or add @JvmExposeBoxed to the constructor to keep it"));
        }
    }

    /**
     * Parses the property whose {@code val}/{@code var} keyword ends at {@code from}, reports the
     * accessor guardrails kapt drops, and returns where scanning resumes. Extension properties are
     * skipped: none was measured.
     */
    private static int property(Decl decl, int from, Known known, List<String> findings) {
        FileContext file = decl.file();
        String code = file.code();
        int j = skipWhitespace(code, from);
        if (decl.scope().body() == Body.OTHER || j >= code.length()
                || !Character.isJavaIdentifierStart(code.charAt(j))) {
            return from;
        }
        int nameEnd = identifierEnd(code, j);
        int k = skipWhitespace(code, nameEnd);
        if (k < code.length() && (code.charAt(k) == '.' || code.charAt(k) == '<')) {
            return from;
        }
        Optional<String> type = Optional.empty();
        if (k < code.length() && code.charAt(k) == ':') {
            type = typeHead(code.substring(k + 1, Math.min(code.length(), k + 1 + 512)))
                .flatMap(head -> resolve(file, head, known));
        }
        accessorFindings(decl, code.substring(j, nameEnd), type, findings);
        return nameEnd;
    }

    /** Reports each {@code @get:}, {@code @set:} and {@code @setparam:} guardrail kapt drops from a property. */
    private static void accessorFindings(Decl decl, String name, Optional<String> type, List<String> findings) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String target : new String[]{"get", "set", "setparam"}) {
            Set<String> names = "setparam".equals(target) ? PARAMETER_GUARDRAILS : FUNCTION_GUARDRAILS;
            List<String> lost = new ArrayList<>();
            for (Ann a : decl.annotations()) {
                if (target.equals(a.useSiteTarget()) && isGuardrail(a, names)) {
                    lost.add("@" + target + ":" + a.simpleName());
                }
            }
            String accessor = "get".equals(target) ? "get" : "set";
            if (!lost.isEmpty() && !decl.annotated(JVM_NAME, accessor)) {
                accessorLoss(decl, accessor, capitalized, type)
                    .ifPresent(why -> findings.add(finding(decl, lost, "property " + name, why)));
            }
        }
    }

    /** Why an accessor guardrail is lost, as the rest of its finding, or empty when it is kept. */
    private static Optional<String> accessorLoss(Decl decl, String accessor, String capitalized,
                                                 Optional<String> type) {
        Body body = decl.scope().body();
        boolean getter = "get".equals(accessor);
        if (body == Body.VALUE_CLASS) {
            return getter && !decl.exposedByContext()
                ? Optional.of("a value class's properties compile to static getters with mangled names (get"
                    + capitalized + "-impl), and kapt leaves them out of the Java stubs" + DROPPED + ON_VALUE_CLASS)
                : Optional.empty();
        }
        // A top-level getter keeps its name (getTopIdGet() in #692); only its setter is mangled.
        if (type.isEmpty() || body == Body.OTHER || (body == Body.TOP && getter)) {
            return Optional.empty();
        }
        String uses = "its " + (getter ? "getter" : "setter") + " uses value class " + type.get();
        if (decl.exposedByContext()) {
            return body == Body.TOP ? Optional.empty() : unexposed(decl, uses);
        }
        String remedy = decl.overridable()
            ? ENCLOSING_TYPE
            : "Add @" + accessor + ":JvmName(\"" + accessor + capitalized + "\") to keep it"
                + (getter ? ", or use @field: when the property has a backing field" : "");
        return Optional.of(uses + ", so its JVM name is mangled and kapt leaves it out of the Java stubs"
            + DROPPED + remedy);
    }

    private static String finding(Decl decl, List<String> lost, String what, String why) {
        return decl.file().source().name() + ":" + lineOf(decl.file().code(), decl.offset()) + " "
            + String.join(", ", lost) + " on " + what + ": " + why + ", or see " + DOCS;
    }

    /**
     * The parameter within {@code span} of {@code code}: its annotations, modifiers, optional
     * {@code val}/{@code var}, name and type text, or empty when it has no {@code name: Type}.
     */
    private static Optional<Param> parseParameter(String code, int[] span) {
        List<Ann> annotations = new ArrayList<>();
        Set<String> modifiers = new HashSet<>();
        int i = skipWhitespace(code, span[0]);
        String word = "";
        while (i < span[1]) {
            if (code.charAt(i) == '@') {
                annotations.add(new Ann(code.substring(i + 1, Math.min(span[1], nameEnd(code, i + 1)))));
                i = skipWhitespace(code, annotationEnd(code, i + 1));
                continue;
            }
            word = wordAt(code, i);
            if (!MODIFIERS.contains(word)) {
                break;
            }
            modifiers.add(word);
            i = skipWhitespace(code, i + word.length());
        }
        String keyword = "val".equals(word) || "var".equals(word) ? word : "";
        int keywordOffset = i;
        if (!keyword.isEmpty()) {
            i = skipWhitespace(code, i + keyword.length());
            word = wordAt(code, i);
        }
        int colon = skipWhitespace(code, i + word.length());
        if (word.isEmpty() || colon >= span[1] || code.charAt(colon) != ':') {
            return Optional.empty();
        }
        String type = code.substring(colon + 1, span[1]).split("=", 2)[0];
        return Optional.of(new Param(List.copyOf(annotations), Set.copyOf(modifiers), keyword, word, type,
            keywordOffset));
    }

    /**
     * The name a type starts with, or empty for a parenthesised type such as a function type, which
     * is not a value class. A function type with a receiver ({@code AccountId.() -> Unit}) yields a
     * head ending in a dot, which resolves to nothing.
     */
    private static Optional<String> typeHead(String type) {
        String t = type.strip();
        if (t.startsWith("(")) {
            return Optional.empty();
        }
        Matcher m = TYPE_HEAD.matcher(t);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    /**
     * Resolves a written type name to a known value class, in Kotlin's order: a qualified name as
     * written, an explicit import, the file's own package, a star import, then the default
     * {@code kotlin.*} import. A name that resolves to anything else, or does not resolve, is empty.
     */
    private static Optional<String> resolve(FileContext file, String head, Known known) {
        Optional<String> resolved;
        String imported = file.explicitImports().get(head);
        String samePackage = qualify(file.pkg(), head);
        if (head.contains(".")) {
            resolved = Optional.of(head);
        } else if (imported != null) {
            resolved = Optional.of(imported);
        } else if (known.declaredTypes().contains(samePackage)) {
            resolved = Optional.of(samePackage);
        } else {
            resolved = file.starImports().stream()
                .map(star -> star + "." + head)
                .filter(c -> known.declaredTypes().contains(c) || STDLIB_VALUE_CLASSES.contains(c))
                .findFirst()
                .or(() -> Optional.of("kotlin." + head));
        }
        return resolved.filter(known.valueClasses()::contains);
    }

    private static boolean isGuardrail(Ann a, Set<String> names) {
        String qualifier = a.qualifier();
        return names.contains(a.simpleName())
            && (qualifier.isEmpty() || ANNOTATIONS_PACKAGE.equals(qualifier));
    }

    private static String qualify(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * The source with every comment and the inside of every string and character literal replaced
     * by spaces, newlines kept, so offsets and line numbers still match the file and nothing a
     * comment or a {@code reason = "..."} says can look like a declaration.
     */
    static String blankCommentsAndStrings(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = blank(text, i, end < 0 ? n : end, sb);
            } else if (text.startsWith("/*", i)) {
                i = blankBlockComment(text, i, sb);
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                sb.append("\"\"\"");
                i = blank(text, i + 3, end < 0 ? n : end, sb);
                if (end >= 0) {
                    sb.append("\"\"\"");
                    i = end + 3;
                }
            } else if (c == '"' || c == '\'') {
                i = blankQuoted(text, i, c, sb);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Kotlin block comments nest, so an inner comment's end does not end the outer one. */
    private static int blankBlockComment(String text, int start, StringBuilder sb) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            if (text.startsWith("/*", i) || text.startsWith("*/", i)) {
                depth += text.charAt(i) == '/' ? 1 : -1;
                sb.append("  ");
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i = blank(text, i, i + 1, sb);
            }
        }
        return i;
    }

    /** A one-line string or character literal; an unterminated one ends at the line break. */
    private static int blankQuoted(String text, int start, char quote, StringBuilder sb) {
        sb.append(quote);
        int i = start + 1;
        int n = text.length();
        while (i < n && text.charAt(i) != quote && text.charAt(i) != '\n') {
            boolean escape = text.charAt(i) == '\\' && i + 1 < n && text.charAt(i + 1) != '\n';
            i = blank(text, i, escape ? i + 2 : i + 1, sb);
        }
        if (i < n && text.charAt(i) == quote) {
            sb.append(quote);
            i++;
        }
        return i;
    }

    /** Appends {@code [from, to)} as spaces, newlines kept; returns {@code to}. */
    private static int blank(String text, int from, int to, StringBuilder sb) {
        for (int i = from; i < to; i++) {
            sb.append(text.charAt(i) == '\n' ? '\n' : ' ');
        }
        return to;
    }

    private static int identifierEnd(String code, int from) {
        int i = from;
        while (i < code.length() && Character.isJavaIdentifierPart(code.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The identifier starting at {@code from}, after any whitespace, or empty. */
    private static String wordAt(String code, int from) {
        int i = skipWhitespace(code, from);
        if (i >= code.length() || !Character.isJavaIdentifierStart(code.charAt(i))) {
            return "";
        }
        return code.substring(i, identifierEnd(code, i));
    }

    /** End of an annotation name: identifier characters, dots, and a use-site target's colon. */
    private static int nameEnd(String code, int from) {
        int i = from;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && c != '.' && c != ':') {
                break;
            }
            i++;
        }
        return i;
    }

    /** End of an annotation, including an argument list written directly after its name. */
    private static int annotationEnd(String code, int from) {
        int i = nameEnd(code, from);
        if (i < code.length() && code.charAt(i) == '(') {
            return closing(code, i, '(', ')') + 1;
        }
        return i;
    }

    /**
     * The {@code (} opening a function's parameter list, outside angle brackets so a generic
     * receiver such as {@code Map<K, V>.name} is skipped; -1 when a character that cannot be part
     * of a function header comes first.
     */
    private static int headerParen(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == '(' && depth == 0) {
                return i;
            } else if ("{};=@".indexOf(c) >= 0) {
                return -1;
            }
        }
        return -1;
    }

    /** The last {@code .} outside angle brackets: where an extension receiver ends, or -1. */
    private static int lastTopLevelDot(String header) {
        int depth = 0;
        int dot = -1;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == '.' && depth == 0) {
                dot = i;
            }
        }
        return dot;
    }

    /** Index of the bracket closing the one at {@code open}, or the text length when unbalanced. */
    private static int closing(String code, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == opener) {
                depth++;
            } else if (c == closer && !isArrowHead(code, i)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return code.length();
    }

    /** Splits {@code [from, to)} of {@code code} at the commas that are not inside brackets. */
    private static List<int[]> splitTopLevel(String code, int from, int to) {
        List<int[]> parts = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = code.charAt(i);
            if ("(<[{".indexOf(c) >= 0) {
                depth++;
            } else if (")>]}".indexOf(c) >= 0 && !isArrowHead(code, i)) {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(new int[]{start, i});
                start = i + 1;
            }
        }
        parts.add(new int[]{start, to});
        return parts;
    }

    /** The {@code >} of a function type's {@code ->}, which closes no bracket. */
    private static boolean isArrowHead(String code, int i) {
        return code.charAt(i) == '>' && i > 0 && code.charAt(i - 1) == '-';
    }

    private static int skipWhitespace(String code, int from) {
        int i = from;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
