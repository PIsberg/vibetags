package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIFeatureFlag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Definition-level tests for v1.0.0 annotations: @AIIdempotent, @AIFeatureFlag.
 */
class NewAnnotationsV5DefinitionTest {

    // ----------------- @AIIdempotent -----------------

    @Test
    void idempotent_hasSourceRetention() {
        Retention r = AIIdempotent.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void idempotent_targetsTypeAndMethod() {
        Target t = AIIdempotent.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, t.value());
    }

    @Test
    void idempotent_reasonDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIIdempotent.class.getDeclaredMethod("reason");
        assertEquals("", m.getDefaultValue());
    }

    // ----------------- @AIFeatureFlag -----------------

    @Test
    void featureFlag_hasSourceRetention() {
        Retention r = AIFeatureFlag.class.getAnnotation(Retention.class);
        assertNotNull(r);
        assertEquals(RetentionPolicy.SOURCE, r.value());
    }

    @Test
    void featureFlag_targetsTypeMethodAndField() {
        Target t = AIFeatureFlag.class.getAnnotation(Target.class);
        assertNotNull(t);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD}, t.value());
    }

    @Test
    void featureFlag_flagDefaultIsEmpty() throws NoSuchMethodException {
        Method m = AIFeatureFlag.class.getDeclaredMethod("flag");
        assertEquals("", m.getDefaultValue());
    }

    @Test
    void featureFlag_defaultValueDefaultIsFalse() throws NoSuchMethodException {
        Method m = AIFeatureFlag.class.getDeclaredMethod("defaultValue");
        assertEquals(false, m.getDefaultValue());
    }
}
