package se.deversity.vibetags.ksp.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITestDriven;

import javax.lang.model.type.MirroredTypeException;
import java.lang.annotation.IncompleteAnnotationException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The annotation instances the processor reads under KSP must behave as javac's do, in the ways
 * {@code AnnotationCollector} and the formatters rely on.
 */
class AnnotationProxiesTest {

    @Test
    void aWrittenMemberReturnsItsValue() {
        AILocked locked = AnnotationProxies.create(AILocked.class,
            new AnnotationData(AILocked.class.getName(), Map.of("reason", "audited")));

        assertEquals("audited", locked.reason());
        assertEquals(AILocked.class, locked.annotationType());
    }

    @Test
    void anUnwrittenMemberReturnsTheDeclaredDefault() {
        AILocked locked = AnnotationProxies.create(AILocked.class,
            new AnnotationData(AILocked.class.getName(), Map.of()));

        assertEquals("Do not modify this code under any circumstances.", locked.reason());
    }

    @Test
    void aRequiredMemberThatWasNeverWrittenThrowsAsUnderJavac() {
        AILoadBearing bearing = AnnotationProxies.create(AILoadBearing.class,
            new AnnotationData(AILoadBearing.class.getName(), Map.of()));

        assertThrows(IncompleteAnnotationException.class, bearing::invariant);
    }

    @Test
    void aClassMemberThrowsMirroredTypeExceptionCarryingTheType() {
        AISunset sunset = AnnotationProxies.create(AISunset.class, new AnnotationData(AISunset.class.getName(),
            Map.of("jira", "API-7", "replacement", new AnnotationData.ClassValue("com.a.NewApi"))));

        MirroredTypeException thrown = assertThrows(MirroredTypeException.class, sunset::replacement);
        assertEquals("com.a.NewApi", thrown.getTypeMirror().toString());
    }

    @Test
    void enumArraysAndNumbersConvertToTheDeclaredTypes() {
        AITestDriven driven = AnnotationProxies.create(AITestDriven.class, new AnnotationData(
            AITestDriven.class.getName(), Map.of(
                "framework", List.of(new AnnotationData.EnumValue(AITestDriven.Framework.class.getName(), "MOCKITO")),
                "coverageGoal", 80L)));

        assertArrayEquals(new AITestDriven.Framework[] {AITestDriven.Framework.MOCKITO}, driven.framework());
        assertEquals(80, driven.coverageGoal());
    }

    @Test
    void aDefaultArrayIsACopyTheCallerCannotCorrupt() {
        AITestDriven driven = AnnotationProxies.create(AITestDriven.class,
            new AnnotationData(AITestDriven.class.getName(), Map.of()));

        driven.framework()[0] = AITestDriven.Framework.NONE;

        assertEquals(AITestDriven.Framework.JUNIT_5, driven.framework()[0]);
    }

    @Test
    void equalityFollowsTypeAndValues() {
        AnnotationData a = new AnnotationData(AILocked.class.getName(), Map.of("reason", "x"));
        AILocked first = AnnotationProxies.create(AILocked.class, a);
        AILocked same = AnnotationProxies.create(AILocked.class, a);
        AILocked other = AnnotationProxies.create(AILocked.class,
            new AnnotationData(AILocked.class.getName(), Map.of("reason", "y")));

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
    }
}
