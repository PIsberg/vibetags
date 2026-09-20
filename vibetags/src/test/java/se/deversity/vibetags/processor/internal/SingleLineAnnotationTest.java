package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * String members of a guardrail annotation are prose, and a Java text block is the natural way to
 * write a long one. Every Markdown platform appends the value after a bullet, so a line break in
 * it ends the bullet and leaves the rest as a bare paragraph. The collector therefore hands out
 * annotations whose string members read as one line, and this test pins the rule on a runtime
 * stand-in: the real {@code @AI*} types are {@code SOURCE}-retained and cannot be read back here.
 */
class SingleLineAnnotationTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Probe {
        String reason() default "";
        String[] tags() default {};
        int weight() default 1;
    }

    @Probe(reason = "one line", tags = {"x"})
    static final class Plain { }

    @Probe(reason = """
        Partner contract v2.
          Breaking it fails the nightly reconciliation.

        Ask the payments team first.""", tags = {"a\nb", "c"}, weight = 3)
    static final class Wrapped { }

    @Probe(reason = "crlf\r\nsecond\rthird")
    static final class Crlf { }

    private static Probe wrap(Class<?> holder) {
        return SingleLineAnnotation.of(Probe.class, holder.getAnnotation(Probe.class));
    }

    @Test
    void aValueWithoutALineBreakIsHandedBackUntouched() {
        Probe out = wrap(Plain.class);
        assertEquals("one line", out.reason());
        assertArrayEquals(new String[] {"x"}, out.tags());
    }

    @Test
    void anAnnotationWithNoStringMembersIsNotWrappedAtAll() {
        Retention raw = Probe.class.getAnnotation(Retention.class);
        assertSame(raw, SingleLineAnnotation.of(Retention.class, raw),
            "nothing to normalise, so nothing to proxy");
    }

    @Test
    void theStringMemberAnswerIsStableAcrossRepeatedCalls() {
        // hasStringMember is memoised per annotation type in a ClassValue, so the answer is now
        // computed once and reused. A memo keyed or invalidated wrongly would show up as the
        // second call disagreeing with the first, or as one type's answer leaking to another.
        Retention raw = Probe.class.getAnnotation(Retention.class);
        for (int call = 0; call < 3; call++) {
            assertEquals("one line", wrap(Plain.class).reason(),
                "call " + call + ": a type with string members must stay proxied");
            assertSame(raw, SingleLineAnnotation.of(Retention.class, raw),
                "call " + call + ": a type without string members must stay unproxied");
        }
    }

    @Test
    void lineBreaksAndTheIndentationAfterThemCollapseToOneSpace() {
        assertEquals("Partner contract v2. Breaking it fails the nightly reconciliation. Ask the payments team first.",
            wrap(Wrapped.class).reason(),
            "a text block reads as one sentence run; blank lines add no extra space");
    }

    @Test
    void arrayMembersAndNonStringMembersFollowTheSameRule() {
        Probe out = wrap(Wrapped.class);
        assertArrayEquals(new String[] {"a b", "c"}, out.tags());
        assertEquals(3, out.weight());
        assertEquals(Probe.class, out.annotationType());
    }

    @Test
    void carriageReturnsCountAsLineBreaksToo() {
        assertEquals("crlf second third", wrap(Crlf.class).reason());
    }
}
