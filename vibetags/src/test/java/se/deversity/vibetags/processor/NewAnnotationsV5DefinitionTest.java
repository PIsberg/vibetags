package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIIdempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Definition-level tests for v1.0.0 annotations: @AIIdempotent.
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
}
