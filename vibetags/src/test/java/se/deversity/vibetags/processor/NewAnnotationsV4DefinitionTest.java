package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISchemaSafe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Definition-level tests for the 9 new AI Guardrail annotations.
 */
class NewAnnotationsV4DefinitionTest {

    // ----------------- @AIParallelTests -----------------

    @Test
    void parallelTests_hasSourceRetention() {
        Retention r = AIParallelTests.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void parallelTests_targetsTypeAndMethod() {
        Target t = AIParallelTests.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    // ----------------- @AILegacyBridge -----------------

    @Test
    void legacyBridge_hasSourceRetention() {
        Retention r = AILegacyBridge.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void legacyBridge_targetsTypeAndMethod() {
        Target t = AILegacyBridge.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    // ----------------- @AIArchitecture -----------------

    @Test
    void architecture_hasSourceRetention() {
        Retention r = AIArchitecture.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void architecture_targetsTypeOnly() {
        Target t = AIArchitecture.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, t.value());
    }

    @Test
    void architecture_belongsToDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIArchitecture.class.getDeclaredMethod("belongsTo");
        assertEquals("", m.getDefaultValue());
    }

    @Test
    void architecture_cannotReferenceDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIArchitecture.class.getDeclaredMethod("cannotReference");
        assertArrayEquals(new String[0], (String[]) m.getDefaultValue());
    }

    // ----------------- @AIPublicAPI -----------------

    @Test
    void publicApi_hasSourceRetention() {
        Retention r = AIPublicAPI.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void publicApi_targetsTypeAndMethod() {
        Target t = AIPublicAPI.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    // ----------------- @AIStrictExceptions -----------------

    @Test
    void strictExceptions_hasSourceRetention() {
        Retention r = AIStrictExceptions.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void strictExceptions_targetsTypeAndMethod() {
        Target t = AIStrictExceptions.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    // ----------------- @AIStrictTypes -----------------

    @Test
    void strictTypes_hasSourceRetention() {
        Retention r = AIStrictTypes.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void strictTypes_targetsTypeMethodField() {
        Target t = AIStrictTypes.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            t.value());
    }

    // ----------------- @AIInternationalized -----------------

    @Test
    void internationalized_hasSourceRetention() {
        Retention r = AIInternationalized.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void internationalized_targetsTypeAndMethod() {
        Target t = AIInternationalized.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    // ----------------- @AIStrictClasspath -----------------

    @Test
    void strictClasspath_hasSourceRetention() {
        Retention r = AIStrictClasspath.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void strictClasspath_targetsTypeAndMethod() {
        Target t = AIStrictClasspath.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    // ----------------- @AISchemaSafe -----------------

    @Test
    void schemaSafe_hasSourceRetention() {
        Retention r = AISchemaSafe.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void schemaSafe_targetsTypeAndField() {
        Target t = AISchemaSafe.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.FIELD}, t.value());
    }

    // ----------------- Application Sanity -----------------

    @AIParallelTests
    static class PT {}

    @AILegacyBridge
    static class LB {}

    @AIArchitecture(belongsTo = "domain", cannotReference = {"infra", "web"})
    static class AR {}

    @AIPublicAPI
    static class PA {}

    @AIStrictExceptions
    static class SE {}

    @AIStrictTypes
    static class ST {}

    @AIInternationalized
    static class IT {}

    @AIStrictClasspath
    static class SC {}

    @AISchemaSafe
    static class SS {}

    @Test
    void all_canBeAppliedToClass() {
        assertDoesNotThrow(() -> {
            assertNotNull(PT.class);
            assertNotNull(LB.class);
            assertNotNull(AR.class);
            assertNotNull(PA.class);
            assertNotNull(SE.class);
            assertNotNull(ST.class);
            assertNotNull(IT.class);
            assertNotNull(SC.class);
            assertNotNull(SS.class);
        });
    }
}
