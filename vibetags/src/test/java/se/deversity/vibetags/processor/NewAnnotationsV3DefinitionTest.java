package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Definition-level tests for the v0.9.5 annotations.
 */
class NewAnnotationsV3DefinitionTest {

    // ----------------- @AIThreadSafe -----------------

    @Test
    void threadSafe_hasSourceRetention() {
        Retention r = AIThreadSafe.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void threadSafe_targetsTypeAndMethod() {
        Target t = AIThreadSafe.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    @Test
    void threadSafe_strategyDefaultIsSynchronized() throws NoSuchMethodException {
        Method m = AIThreadSafe.class.getDeclaredMethod("strategy");
        assertEquals(AIThreadSafe.Strategy.SYNCHRONIZED, m.getDefaultValue());
    }

    @Test
    void threadSafe_strategyEnumIncludesAllExpectedValues() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (AIThreadSafe.Strategy s : AIThreadSafe.Strategy.values()) names.add(s.name());
        assertTrue(names.contains("SYNCHRONIZED"));
        assertTrue(names.contains("LOCK_FREE"));
        assertTrue(names.contains("IMMUTABLE"));
        assertTrue(names.contains("THREAD_LOCAL"));
        assertTrue(names.contains("OTHER"));
    }

    @Test
    void threadSafe_noteDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIThreadSafe.class.getDeclaredMethod("note");
        assertEquals("", m.getDefaultValue());
    }

    // ----------------- @AIImmutable -----------------

    @Test
    void immutable_hasSourceRetention() {
        Retention r = AIImmutable.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void immutable_targetsTypeOnly() {
        Target t = AIImmutable.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, t.value());
    }

    @Test
    void immutable_noteDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIImmutable.class.getDeclaredMethod("note");
        assertEquals("", m.getDefaultValue());
    }

    // ----------------- @AIDeprecated -----------------

    @Test
    void deprecated_hasSourceRetention() {
        Retention r = AIDeprecated.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void deprecated_targetsTypeMethodField() {
        Target t = AIDeprecated.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            t.value());
    }

    @Test
    void deprecated_replacedByDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIDeprecated.class.getDeclaredMethod("replacedBy");
        assertEquals("", m.getDefaultValue());
    }

    @Test
    void deprecated_migrationGuideHasNonEmptyDefault() throws NoSuchMethodException {
        Method m = AIDeprecated.class.getDeclaredMethod("migrationGuide");
        String def = (String) m.getDefaultValue();
        assertNotNull(def);
        assertFalse(def.isBlank());
    }

    @Test
    void deprecated_deadlineDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIDeprecated.class.getDeclaredMethod("deadline");
        assertEquals("", m.getDefaultValue());
    }

    // ----------------- @AIObservability -----------------

    @Test
    void observability_hasSourceRetention() {
        Retention r = AIObservability.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void observability_targetsTypeAndMethod() {
        Target t = AIObservability.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    @Test
    void observability_arrayDefaultsAreEmpty() throws NoSuchMethodException {
        for (String name : new String[]{"metrics", "traces", "logs"}) {
            Method m = AIObservability.class.getDeclaredMethod(name);
            assertArrayEquals(new String[0], (String[]) m.getDefaultValue(),
                name + " must default to empty array");
        }
    }

    @Test
    void observability_noteDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIObservability.class.getDeclaredMethod("note");
        assertEquals("", m.getDefaultValue());
    }

    // ----------------- @AIRegulation -----------------

    @Test
    void regulation_hasSourceRetention() {
        Retention r = AIRegulation.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void regulation_targetsTypeMethodField() {
        Target t = AIRegulation.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            t.value());
    }

    @Test
    void regulation_standardHasNoDefault() throws NoSuchMethodException {
        Method m = AIRegulation.class.getDeclaredMethod("standard");
        assertNull(m.getDefaultValue(), "standard() must be required (no default)");
    }

    @Test
    void regulation_clauseDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIRegulation.class.getDeclaredMethod("clause");
        assertEquals("", m.getDefaultValue());
    }

    @Test
    void regulation_descriptionDefaultIsNonBlank() throws NoSuchMethodException {
        Method m = AIRegulation.class.getDeclaredMethod("description");
        String def = (String) m.getDefaultValue();
        assertNotNull(def);
        assertFalse(def.isBlank());
    }

    // ----------------- application sanity -----------------

    @AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE)
    static class TS {}
    @AIImmutable
    static class IM {}
    @AIDeprecated(replacedBy = "x.NewType")
    static class DEP {}
    @AIObservability(metrics = {"m"})
    static class OB {}
    @AIRegulation(standard = "GDPR", clause = "Art. 17")
    static class RG {}

    @Test
    void all_canBeAppliedToClass() {
        assertDoesNotThrow(() -> {
            assertNotNull(TS.class);
            assertNotNull(IM.class);
            assertNotNull(DEP.class);
            assertNotNull(OB.class);
            assertNotNull(RG.class);
        });
    }
}
