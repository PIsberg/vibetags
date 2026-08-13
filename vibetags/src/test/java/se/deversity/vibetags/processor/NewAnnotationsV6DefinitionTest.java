package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIThreadAffinity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Definition-level tests for the v1.0.0 evidence-based wave — {@code @AIGenerated},
 * {@code @AILoadBearing}, {@code @AIBannedApi}, {@code @AIThreadAffinity}, {@code @AIKeepInSync}
 * (see {@code docs/proposed-annotations.md}).
 *
 * <p>Reflection only: retention, targets, and attribute defaults. SOURCE retention is a hard
 * invariant of the library — anything else would give VibeTags a runtime footprint.
 */
class NewAnnotationsV6DefinitionTest {

    private static void assertSourceRetention(Class<?> annotation) {
        Retention retention = annotation.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.SOURCE, retention.value(),
            annotation.getSimpleName() + " must use SOURCE retention — zero runtime cost is a hard invariant");
    }

    private static Object defaultOf(Class<?> annotation, String attribute) throws NoSuchMethodException {
        return annotation.getDeclaredMethod(attribute).getDefaultValue();
    }

    // ------------------------------------------------------------------
    // @AIGenerated
    // ------------------------------------------------------------------

    @Test
    void aiGenerated_retentionAndTargets() {
        assertSourceRetention(AIGenerated.class);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            AIGenerated.class.getAnnotation(Target.class).value());
    }

    @Test
    void aiGenerated_fromIsRequired_othersDefaultToEmpty() throws NoSuchMethodException {
        assertNull(defaultOf(AIGenerated.class, "from"),
            "'from' must be required — an agent that does not know the source cannot take the redirect");
        assertEquals("", defaultOf(AIGenerated.class, "regenerateWith"));
        assertEquals("", defaultOf(AIGenerated.class, "editInstead"));
    }

    // ------------------------------------------------------------------
    // @AILoadBearing
    // ------------------------------------------------------------------

    @Test
    void aiLoadBearing_retentionAndTargets() {
        assertSourceRetention(AILoadBearing.class);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER},
            AILoadBearing.class.getAnnotation(Target.class).value());
    }

    @Test
    void aiLoadBearing_invariantIsRequired_suppressAuditDefaultsFalse() throws NoSuchMethodException {
        assertNull(defaultOf(AILoadBearing.class, "invariant"),
            "'invariant' must be required — without it the annotation says only 'this is odd'");
        assertEquals("", defaultOf(AILoadBearing.class, "breaksIf"));
        assertEquals(Boolean.FALSE, defaultOf(AILoadBearing.class, "suppressAudit"));
    }

    // ------------------------------------------------------------------
    // @AIBannedApi
    // ------------------------------------------------------------------

    @Test
    void aiBannedApi_retentionAndTargets() {
        assertSourceRetention(AIBannedApi.class);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE},
            AIBannedApi.class.getAnnotation(Target.class).value());
    }

    @Test
    void aiBannedApi_forbiddenIsRequired_othersDefaultToEmpty() throws NoSuchMethodException {
        assertNull(defaultOf(AIBannedApi.class, "forbidden"));
        assertEquals("", defaultOf(AIBannedApi.class, "useInstead"));
        assertEquals("", defaultOf(AIBannedApi.class, "reason"));
    }

    // ------------------------------------------------------------------
    // @AIThreadAffinity
    // ------------------------------------------------------------------

    @Test
    void aiThreadAffinity_retentionAndTargets() {
        assertSourceRetention(AIThreadAffinity.class);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD},
            AIThreadAffinity.class.getAnnotation(Target.class).value());
    }

    @Test
    void aiThreadAffinity_valueHasNoDefault() throws NoSuchMethodException {
        assertNull(defaultOf(AIThreadAffinity.class, "value"),
            "every Affinity constant is a different, mutually exclusive claim — there is no safe default");
        assertEquals("", defaultOf(AIThreadAffinity.class, "thread"));
        assertEquals("", defaultOf(AIThreadAffinity.class, "marshalVia"));
        assertEquals("", defaultOf(AIThreadAffinity.class, "symptomIfViolated"));
    }

    @Test
    void aiThreadAffinity_declaresTheFourAffinities() {
        assertArrayEquals(
            new AIThreadAffinity.Affinity[]{
                AIThreadAffinity.Affinity.MAIN_ONLY,
                AIThreadAffinity.Affinity.NEVER_MAIN,
                AIThreadAffinity.Affinity.BACKGROUND_ONLY,
                AIThreadAffinity.Affinity.NAMED},
            AIThreadAffinity.Affinity.values());
    }

    // ------------------------------------------------------------------
    // @AIKeepInSync
    // ------------------------------------------------------------------

    @Test
    void aiKeepInSync_retentionAndTargets() {
        assertSourceRetention(AIKeepInSync.class);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            AIKeepInSync.class.getAnnotation(Target.class).value());
    }

    @Test
    void aiKeepInSync_mirrorsIsRequired_othersDefaultToEmpty() throws NoSuchMethodException {
        assertNull(defaultOf(AIKeepInSync.class, "mirrors"));
        assertEquals("", defaultOf(AIKeepInSync.class, "reason"));
        assertEquals("", defaultOf(AIKeepInSync.class, "enforcedBy"));
    }
}
