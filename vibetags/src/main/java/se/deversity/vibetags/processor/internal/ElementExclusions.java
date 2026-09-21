package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The value of {@code -Avibetags.exclude}: which annotated elements are collected but never
 * published.
 *
 * <p>Collected and not published is the entire point, and it is why this is not a compiler
 * {@code <exclude>}. Leaving an annotated source out of the compilation makes
 * {@link PartialRoundDetector} see a round that was not shown every annotated source in its module,
 * and invariant 17 then correctly refuses to write anything at all
 * (<a href="https://github.com/PIsberg/vibetags/issues/792">issue #792</a>). An element matched
 * here is still collected, so the source ledger is satisfied and the round writes as usual; it is
 * dropped one step later, when the model is handed to the renderers.
 *
 * <h2>Pattern form</h2>
 *
 * <p>A comma-separated list of globs, each matched against an element's
 * {@linkplain TaggedElement#path() path}: {@code *} matches any run of characters including dots,
 * {@code ?} matches exactly one, and everything else is literal. A pattern with no wildcard is an
 * exact path.
 *
 * <p>The path, not {@link TaggedElement#qualifiedName()}, because the path carries the enclosing
 * type ({@code com.example.FooTest.shouldExport()}) while the qualified name deliberately does not.
 * A pattern naming a fixture class is meant to take its annotated methods with it, and against the
 * qualified name it silently would not. It is also the identity the generated files print in their
 * own {@code path="..."} attributes, so a pattern can be written by copying from the output it is
 * meant to shrink.
 *
 * <p>{@code /} is read as {@code .} and {@code **} as {@code *}, so a path-shaped pattern copied
 * out of a build file, {@code **}{@code /*FixtureTest*}, means what its author intended rather than
 * silently matching nothing. Matching is on the name and never on the file, because that is the
 * only identity an element still has by the time the model is rendered.
 */
public final class ElementExclusions {

    /** Excludes nothing, which is what every build that does not pass the option gets. */
    public static final ElementExclusions NONE = new ElementExclusions(List.of(), List.of());

    /** The patterns as written, kept verbatim for the fingerprint and for diagnostics. */
    private final List<String> patterns;
    private final List<Pattern> compiled;

    private ElementExclusions(List<String> patterns, List<Pattern> compiled) {
        this.patterns = List.copyOf(patterns);
        this.compiled = List.copyOf(compiled);
    }

    /**
     * Parses an option value. A null, blank or all-empty value yields {@link #NONE}, so passing
     * {@code -Avibetags.exclude=} is the same as not passing it rather than an error.
     */
    public static ElementExclusions parse(@Nullable String optionValue) {
        if (optionValue == null || optionValue.isBlank()) {
            return NONE;
        }
        List<String> patterns = new ArrayList<>();
        List<Pattern> compiled = new ArrayList<>();
        for (String raw : optionValue.split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            patterns.add(pattern);
            compiled.add(Pattern.compile(regexFor(pattern)));
        }
        return patterns.isEmpty() ? NONE : new ElementExclusions(patterns, compiled);
    }

    /** Whether any pattern was given; {@code false} is the default and the fast path. */
    public boolean isEmpty() {
        return compiled.isEmpty();
    }

    /** The patterns as written. Folded into {@link BuildFingerprint} so toggling one regenerates. */
    public List<String> patterns() {
        return patterns;
    }

    /** Whether {@code element} is excluded from everything this build publishes. */
    public boolean excludes(TaggedElement element) {
        if (compiled.isEmpty()) {
            return false;
        }
        // path(), not qualifiedName(). qualifiedName() is the element's own name "without any
        // enclosing-type prefix", so a method fixture's is the bare method name and a pattern
        // naming its enclosing class cannot match it: excluding AnnotationDefinitionsTest* took out
        // the nested classes and left every annotated method behind. path() is the identity the
        // generated files print in their own path="..." attributes, so a pattern is written against
        // what the reader can see in the output they are trying to shrink.
        String name = element.path();
        for (Pattern pattern : compiled) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One glob as an anchored regex. Everything outside the two wildcards is quoted, so a pattern
     * containing {@code $}, {@code (} or a {@code .} means those characters and not their regex
     * senses — a name is full of dots, and treating them as "any character" would make
     * {@code com.example.Fixture} match {@code comXexampleXFixture} and, more to the point, make
     * every pattern quietly broader than it reads.
     */
    private static String regexFor(String glob) {
        String normalised = glob.replace('/', '.');
        // "**" is "*" here: a name has no directory levels for the two to differ over, and a
        // path-shaped pattern is a likely thing to paste in.
        while (normalised.contains("**")) {
            normalised = normalised.replace("**", "*");
        }
        StringBuilder regex = new StringBuilder(normalised.length() + 16);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < normalised.length(); i++) {
            char c = normalised.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }
}
