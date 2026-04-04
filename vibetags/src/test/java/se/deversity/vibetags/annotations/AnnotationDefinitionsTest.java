package se.deversity.vibetags.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for annotation definitions and their attributes.
 */
class AnnotationDefinitionsTest {

    @Test
    void testAILockedAnnotationRetention() {
        // Verify @AILocked has SOURCE retention
        Retention retention = AILocked.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAILockedAnnotationTargets() {
        // Verify @AILocked targets TYPE, METHOD, FIELD
        Target target = AILocked.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            target.value()
        );
    }

    @Test
    void testAILockedDefaultValue() throws NoSuchMethodException {
        Method reasonMethod = AILocked.class.getDeclaredMethod("reason");
        assertNotNull(reasonMethod.getDefaultValue());
        assertEquals(
            "Do not modify this code under any circumstances.",
            reasonMethod.getDefaultValue()
        );
    }

    @Test
    void testAIContextAnnotationRetention() {
        Retention retention = AIContext.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIContextAnnotationTargets() {
        Target target = AIContext.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD},
            target.value()
        );
    }

    @Test
    void testAIContextDefaultValues() throws NoSuchMethodException {
        Method focusMethod = AIContext.class.getDeclaredMethod("focus");
        Method avoidsMethod = AIContext.class.getDeclaredMethod("avoids");
        
        assertEquals("", focusMethod.getDefaultValue());
        assertEquals("", avoidsMethod.getDefaultValue());
    }

    @Test
    void testAIDraftAnnotationRetention() {
        Retention retention = AIDraft.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIDraftAnnotationTargets() {
        Target target = AIDraft.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD},
            target.value()
        );
    }

    @Test
    void testAIDraftDefaultValue() throws NoSuchMethodException {
        Method instructionsMethod = AIDraft.class.getDeclaredMethod("instructions");
        assertNotNull(instructionsMethod.getDefaultValue());
        assertEquals(
            "Implement this method/class according to standard practices.",
            instructionsMethod.getDefaultValue()
        );
    }

    @Test
    void testAIAuditAnnotationRetention() {
        Retention retention = AIAudit.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIAuditAnnotationTargets() {
        Target target = AIAudit.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD},
            target.value()
        );
    }

    @Test
    void testAIAuditCheckForDefaultValue() throws NoSuchMethodException {
        Method checkForMethod = AIAudit.class.getDeclaredMethod("checkFor");
        assertNotNull(checkForMethod.getDefaultValue());
        assertArrayEquals(new String[0], (String[]) checkForMethod.getDefaultValue());
    }

    @Test
    @AILocked(reason = "Test reason")
    void testAILockedCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAILockedCanBeUsedOnMethods");
        AILocked locked = method.getAnnotation(AILocked.class);
        // Note: SOURCE retention annotations won't be available at runtime
        // This test verifies the annotation can be applied without compiler errors
        assertNull(locked); // Expected: null because SOURCE retention
    }

    @Test
    @AIContext(focus = "Test focus", avoids = "Test avoids")
    void testAIContextCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIContextCanBeUsedOnMethods");
        AIContext context = method.getAnnotation(AIContext.class);
        assertNull(context); // Expected: null because SOURCE retention
    }

    @Test
    @AIDraft(instructions = "Test instructions")
    void testAIDraftCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIDraftCanBeUsedOnMethods");
        AIDraft draft = method.getAnnotation(AIDraft.class);
        assertNull(draft); // Expected: null because SOURCE retention
    }

    @Test
    @AIAudit(checkFor = {"SQL Injection", "Thread Safety"})
    void testAIAuditCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIAuditCanBeUsedOnMethods");
        AIAudit audit = method.getAnnotation(AIAudit.class);
        assertNull(audit); // Expected: null because SOURCE retention
    }

    @Test
    void testAIIgnoreAnnotationRetention() {
        Retention retention = AIIgnore.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIIgnoreAnnotationTargets() {
        Target target = AIIgnore.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            target.value()
        );
    }

    @Test
    void testAIIgnoreDefaultValue() throws NoSuchMethodException {
        Method reasonMethod = AIIgnore.class.getDeclaredMethod("reason");
        assertNotNull(reasonMethod.getDefaultValue());
        assertEquals("Excluded from AI context.", reasonMethod.getDefaultValue());
    }

    @Test
    @AIIgnore(reason = "Test reason")
    void testAIIgnoreCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIIgnoreCanBeUsedOnMethods");
        AIIgnore ignore = method.getAnnotation(AIIgnore.class);
        assertNull(ignore); // Expected: null because SOURCE retention
    }

    @AILocked
    static class TestLockedClass {
    }

    @AIContext(focus = "test")
    static class TestContextClass {
    }

    @AIAudit(checkFor = {"XSS"})
    static class TestAuditClass {
    }

    @AIIgnore(reason = "Auto-generated code")
    static class TestIgnoreClass {
    }

    @Test
    void testAnnotationsCanBeAppliedToClasses() {
        // These tests verify annotations can be applied to class declarations
        // No runtime verification needed since SOURCE retention
        assertDoesNotThrow(() -> {
            Class<?> lockedClass = TestLockedClass.class;
            Class<?> contextClass = TestContextClass.class;
            Class<?> auditClass = TestAuditClass.class;
            Class<?> ignoreClass = TestIgnoreClass.class;

            assertNotNull(lockedClass);
            assertNotNull(contextClass);
            assertNotNull(auditClass);
            assertNotNull(ignoreClass);
        });
    }
}
