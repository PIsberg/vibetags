package se.deversity.vibetags.cli;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
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
 * Finds {@code @AI*} guardrails on Kotlin functions that kapt leaves out of its Java stubs.
 *
 * <p>A function that takes or returns a value class gets a mangled JVM name
 * ({@code balanceFor-oKSF6Yo}), and kapt omits every such function from the stub it hands to
 * annotation processors, so a guardrail on the function or on one of its parameters generates
 * nothing and logs nothing (<a href="https://github.com/PIsberg/vibetags/issues/681">#681</a>).
 * The processor cannot warn: the function is not in what it sees, and the stub's
 * {@code @kotlin.Metadata} does not record {@code SOURCE}-retention annotations. Doctor can read
 * the {@code .kt} files, so it reports them instead
 * (<a href="https://github.com/PIsberg/vibetags/issues/688">#688</a>).
 *
 * <p>This is a heuristic over source text, not a compiler. Each rule is chosen so that a doubt
 * produces a miss rather than a false finding:
 * <ul>
 *   <li>A type counts only when it resolves, through the file's package and imports, to a value
 *       class declared in the scanned sources or to one of {@link #STDLIB_VALUE_CLASSES}. Value
 *       classes from other modules or dependencies, type aliases and nested-name references
 *       ({@code Outer.Id}) are not resolved.</li>
 *   <li>Only the head of a parameter or return type counts: {@code List<AccountId>} is not
 *       mangled (measured in #689), {@code AccountId?} is.</li>
 *   <li>{@code kotlin.Result} is never a hit: it is a value class the compiler does not mangle as
 *       a parameter ({@code settle(java.lang.Object)} in {@code examples/kotlin}).</li>
 *   <li>A function carrying {@code @JvmName} is skipped: an explicit JVM name switches mangling
 *       off, and kapt then emits the function (measured in #689).</li>
 *   <li>Extension receivers, {@code vararg} parameters, suspend return types, constructors and
 *       properties are not reported: none of them was measured against kapt.</li>
 *   <li>A file mentioning {@code @JvmExposeBoxed} is skipped, and so are the value classes it
 *       declares: the boxed variant that annotation adds was not measured against kapt either.</li>
 * </ul>
 */
final class KotlinValueClassScan {

    /**
     * Standard-library value classes. The unsigned types: "Unsigned numbers are implemented as
     * inline classes" (kotlinlang.org/docs/unsigned-integer-types.html). {@code Duration} is
     * declared {@code @JvmInline value class Duration}
     * (kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-duration/), and a function returning it
     * was measured absent from kapt's stub in #689. {@code kotlin.Result} is a value class too and
     * is left out on purpose: it is not mangled.
     */
    static final Set<String> STDLIB_VALUE_CLASSES = Set.of(
        "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong", "kotlin.time.Duration");

    private static final String ANNOTATIONS_PACKAGE = "se.deversity.vibetags.annotations";

    /** Words that may sit between a function's annotations and its {@code fun} keyword. */
    private static final Set<String> MODIFIERS = Set.of(
        "public", "private", "protected", "internal", "override", "open", "final", "abstract",
        "suspend", "inline", "operator", "infix", "tailrec", "external", "actual", "expect");

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

    /** A Kotlin source file as doctor read it: the name to print, and its text. */
    record Source(String name, String text) {
    }

    /** What the scan found: one finding per function that loses guardrails. */
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

    private KotlinValueClassScan() {
    }

    static Report scan(List<Source> sources) {
        List<FileContext> files = new ArrayList<>();
        Set<String> valueClasses = new HashSet<>();
        Set<String> otherTypes = new HashSet<>();
        Set<String> exposed = new HashSet<>();
        for (Source source : sources) {
            FileContext file = parse(source);
            files.add(file);
            Matcher m = DECLARATION.matcher(file.code());
            while (m.find()) {
                String fqn = qualify(file.pkg(), m.group(2));
                if (m.group(1) == null) {
                    otherTypes.add(fqn);
                } else {
                    valueClasses.add(fqn);
                    if (exposesBoxed(file)) {
                        exposed.add(fqn);
                    }
                }
            }
        }
        // A name declared both ways (a nested value class next to a same-named top-level class)
        // cannot be told apart by text, so it is dropped rather than guessed.
        valueClasses.removeAll(otherTypes);
        valueClasses.removeAll(exposed);
        Set<String> declaredTypes = new HashSet<>(otherTypes);
        declaredTypes.addAll(valueClasses);
        int declared = valueClasses.size();
        valueClasses.addAll(STDLIB_VALUE_CLASSES);
        Known known = new Known(valueClasses, declaredTypes);

        List<String> findings = new ArrayList<>();
        for (FileContext file : files) {
            if (!exposesBoxed(file)) {
                scanFunctions(file, known, findings);
            }
        }
        return new Report(findings, declared);
    }

    /**
     * {@code @JvmExposeBoxed} on a value class or a function makes the compiler emit a boxed,
     * unmangled variant (kotlinlang.org/docs/java-to-kotlin-interop.html). Whether kapt's stub then
     * carries it was not measured, so a file that mentions it anywhere contributes no value classes
     * and no findings.
     */
    private static boolean exposesBoxed(FileContext file) {
        return file.code().contains("JvmExposeBoxed");
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
     * Walks the file collecting annotations until a declaration keyword binds them. Anything that
     * is not an annotation, a modifier or {@code fun} discards what was collected, so an
     * annotation never travels past the declaration it belongs to.
     */
    private static void scanFunctions(FileContext file, Known known, List<String> findings) {
        String code = file.code();
        List<Ann> pending = new ArrayList<>();
        boolean suspend = false;
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '@') {
                pending.add(new Ann(code.substring(i + 1, nameEnd(code, i + 1))));
                i = annotationEnd(code, i + 1);
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(code, i);
                String word = code.substring(i, end);
                i = end;
                if ("fun".equals(word)) {
                    i = function(file, end, pending, suspend, known, findings);
                }
                if ("suspend".equals(word)) {
                    suspend = true;
                } else if (!MODIFIERS.contains(word)) {
                    pending.clear();
                    suspend = false;
                }
            } else {
                if (!Character.isWhitespace(c)) {
                    pending.clear();
                    suspend = false;
                }
                i++;
            }
        }
    }

    /**
     * Parses the function whose {@code fun} keyword ends at {@code from}, records a finding when it
     * loses guardrails, and returns where scanning resumes.
     */
    private static int function(FileContext file, int from, List<Ann> annotations, boolean suspend,
                                Known known, List<String> findings) {
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
        String name = header.substring(header.lastIndexOf('.') + 1).strip();
        int close = closing(code, open, '(', ')');
        if (name.isEmpty() || close >= code.length()) {
            return from;
        }
        boolean jvmName = annotations.stream()
            .anyMatch(a -> "JvmName".equals(a.simpleName()) && !"file".equals(a.useSiteTarget()));
        if (jvmName) {
            return close + 1;
        }

        List<String> lost = new ArrayList<>();
        for (Ann a : annotations) {
            if (a.useSiteTarget().isEmpty() && isGuardrail(a, FUNCTION_GUARDRAILS)) {
                lost.add("@" + a.simpleName());
            }
        }
        Set<String> mangledBy = new TreeSet<>();
        for (String parameter : splitTopLevel(code.substring(open + 1, close))) {
            parameter(file, parameter, known, lost, mangledBy);
        }
        int k = skipWhitespace(code, close + 1);
        if (!suspend && k < code.length() && code.charAt(k) == ':') {
            typeHead(code.substring(k + 1, Math.min(code.length(), k + 1 + 512)))
                .flatMap(head -> resolve(file, head, known))
                .ifPresent(mangledBy::add);
        }
        if (!lost.isEmpty() && !mangledBy.isEmpty()) {
            findings.add(file.source().name() + ":" + lineOf(code, from) + " "
                + String.join(", ", lost) + " on fun " + name + ": its signature uses value class "
                + String.join(", ", mangledBy) + ", so its JVM name is mangled and kapt leaves it out "
                + "of the Java stubs; the guardrail is dropped before any processor sees it. Add "
                + "@JvmName(\"" + name + "\") to the function to keep it, or see docs/JVM-LANGUAGES.md, "
                + "Kotlin");
        }
        return close + 1;
    }

    /** One parameter: its guardrails join {@code lost}, and its type may join {@code mangledBy}. */
    private static void parameter(FileContext file, String parameter, Known known, List<String> lost,
                                  Set<String> mangledBy) {
        String rest = parameter.strip();
        List<Ann> own = new ArrayList<>();
        while (rest.startsWith("@")) {
            own.add(new Ann(rest.substring(1, nameEnd(rest, 1))));
            rest = rest.substring(Math.min(rest.length(), annotationEnd(rest, 1))).strip();
        }
        int colon = rest.indexOf(':');
        if (rest.startsWith("vararg ") || colon < 0) {
            return;   // vararg was not measured against kapt; no colon means nothing to resolve
        }
        String paramName = rest.substring(0, colon).replaceFirst("^(?:noinline|crossinline)\\s+", "").strip();
        typeHead(rest.substring(colon + 1).split("=", 2)[0])
            .flatMap(head -> resolve(file, head, known))
            .ifPresent(mangledBy::add);
        for (Ann a : own) {
            String target = a.useSiteTarget();
            if ((target.isEmpty() || "param".equals(target)) && isGuardrail(a, PARAMETER_GUARDRAILS)) {
                lost.add("@" + a.simpleName() + " (parameter " + paramName + ")");
            }
        }
    }

    /**
     * The name a type starts with, or empty for a function type or a parenthesised type, neither
     * of which is a value class.
     */
    private static Optional<String> typeHead(String type) {
        String t = type.strip();
        if (t.startsWith("(") || t.contains("->")) {
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

    /** Splits a parameter list at the commas that are not inside brackets. */
    private static List<String> splitTopLevel(String params) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if ("(<[{".indexOf(c) >= 0) {
                depth++;
            } else if (")>]}".indexOf(c) >= 0 && !isArrowHead(params, i)) {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
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
