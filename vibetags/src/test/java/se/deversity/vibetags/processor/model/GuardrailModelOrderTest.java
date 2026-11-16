package se.deversity.vibetags.processor.model;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The model's output order is a function of what it holds, never of the order it was given.
 *
 * <p>Insertion order is {@code RoundEnvironment} order, which differs between Maven and Gradle and
 * between machines, so any tie the model leaves unbroken becomes generated content that differs
 * from one developer's build to the next. {@code OutputOrderDeterminismTest} pins the common case
 * (distinct paths) end to end; these pin the tie-breakers it never reaches. Each was shown to go
 * green with its tie-breaker deleted before this test existed.
 *
 * <p>Every case builds the same content twice, in opposite insertion orders, and requires the same
 * iteration order. The expected order is written out, not read back from the code under test.
 */
class GuardrailModelOrderTest {

    private static TaggedElement element(String path, ElementTag kind) {
        return TaggedElement.builder(path)
            .names(path, path.substring(path.lastIndexOf('.') + 1), path, path.replace('.', '-'))
            .kind(kind)
            .build();
    }

    private static TransitiveRule rule(Map<String, String> members) {
        return new TransitiveRule("com.acme:lib:1.0", "com.acme.api", "@AIContext",
            TransitiveRule.Tier.ADVISORY, members);
    }

    private static List<ElementTag> kindsIn(GuardrailModel model, Class<AILocked> type) {
        List<ElementTag> kinds = new ArrayList<>();
        model.of(type).forEach(e -> kinds.add(e.kind()));
        return kinds;
    }

    /**
     * A record component and the field javac derives from it can share one path and differ only
     * in kind; equality is {@code (path, kind)}, so both stay in the bucket. Ordered by kind name.
     */
    @Test
    void elementsSharingAPathAreOrderedByKindNotByArrival() {
        TaggedElement field = element("com.acme.Point.x", ElementTag.FIELD);
        TaggedElement component = element("com.acme.Point.x", ElementTag.RECORD_COMPONENT);

        GuardrailModel fieldFirst = GuardrailModel.builder()
            .add(AILocked.class, field).add(AILocked.class, component).build();
        GuardrailModel componentFirst = GuardrailModel.builder()
            .add(AILocked.class, component).add(AILocked.class, field).build();

        List<ElementTag> expected = List.of(ElementTag.FIELD, ElementTag.RECORD_COMPONENT);
        assertEquals(expected, kindsIn(fieldFirst, AILocked.class));
        assertEquals(expected, kindsIn(componentFirst, AILocked.class));
    }

    /** The path still decides first: kind only breaks a tie. */
    @Test
    void pathOutranksKind() {
        TaggedElement method = element("com.acme.Alpha.run()", ElementTag.METHOD);
        TaggedElement type = element("com.acme.Zulu", ElementTag.CLASS);

        GuardrailModel model = GuardrailModel.builder()
            .add(AIContext.class, type).add(AIContext.class, method).build();

        List<String> paths = new ArrayList<>();
        model.of(AIContext.class).forEach(e -> paths.add(e.path()));
        assertEquals(List.of("com.acme.Alpha.run()", "com.acme.Zulu"), paths);
    }

    /**
     * Two rules from the same artifact, package and annotation that differ only in their
     * attributes: the attribute summary is the last tie-breaker, so they render and fingerprint
     * in the same order whichever manifest was walked first.
     */
    @Test
    void inheritedRulesDifferingOnlyInAttributesAreOrderedByThem() {
        TransitiveRule beta = rule(Map.of("focus", "beta"));
        TransitiveRule alpha = rule(Map.of("focus", "alpha"));

        GuardrailModel betaFirst = GuardrailModel.builder()
            .transitiveRule(beta).transitiveRule(alpha).build();
        GuardrailModel alphaFirst = GuardrailModel.builder()
            .transitiveRule(alpha).transitiveRule(beta).build();

        assertEquals(List.of(alpha, beta), betaFirst.transitiveRules());
        assertEquals(List.of(alpha, beta), alphaFirst.transitiveRules());
    }
}
