package se.deversity.vibetags.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIPerformance;

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

    @AIPrivacy(reason = "Test PII field")
    static class TestPrivacyClass {
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
            Class<?> privacyClass = TestPrivacyClass.class;

            assertNotNull(lockedClass);
            assertNotNull(contextClass);
            assertNotNull(auditClass);
            assertNotNull(ignoreClass);
            assertNotNull(privacyClass);
        });
    }

    @Test
    void testAIPrivacyAnnotationRetention() {
        Retention retention = AIPrivacy.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIPrivacyAnnotationTargets() {
        Target target = AIPrivacy.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            target.value()
        );
    }

    @Test
    void testAIPrivacyDefaultValue() throws NoSuchMethodException {
        Method reasonMethod = AIPrivacy.class.getDeclaredMethod("reason");
        assertNotNull(reasonMethod.getDefaultValue());
        String defaultReason = (String) reasonMethod.getDefaultValue();
        assertFalse(defaultReason.isBlank(), "default reason must not be blank");
        assertTrue(defaultReason.contains("PII"), "default reason should mention PII");
    }

    @Test
    @AIPrivacy(reason = "Test reason")
    void testAIPrivacyCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIPrivacyCanBeUsedOnMethods");
        AIPrivacy privacy = method.getAnnotation(AIPrivacy.class);
        assertNull(privacy); // Expected: null because SOURCE retention
    }

    // -----------------------------------------------------------------------
    // @AICore
    // -----------------------------------------------------------------------

    @Test
    void testAICoreAnnotationRetention() {
        Retention retention = AICore.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAICoreAnnotationTargets() {
        Target target = AICore.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            target.value()
        );
    }

    @Test
    void testAICoreDefaultValues() throws NoSuchMethodException {
        Method sensitivityMethod = AICore.class.getDeclaredMethod("sensitivity");
        Method noteMethod = AICore.class.getDeclaredMethod("note");
        assertEquals("High", sensitivityMethod.getDefaultValue());
        assertNotNull(noteMethod.getDefaultValue());
        assertFalse(((String) noteMethod.getDefaultValue()).isBlank(),
            "default note must not be blank");
    }

    @Test
    @AICore(sensitivity = "Critical", note = "Test core logic")
    void testAICoreCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAICoreCanBeUsedOnMethods");
        AICore core = method.getAnnotation(AICore.class);
        assertNull(core); // Expected: null because SOURCE retention
    }

    // -----------------------------------------------------------------------
    // @AIPerformance
    // -----------------------------------------------------------------------

    @Test
    void testAIPerformanceAnnotationRetention() {
        Retention retention = AIPerformance.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIPerformanceAnnotationTargets() {
        Target target = AIPerformance.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD, ElementType.FIELD},
            target.value()
        );
    }

    @Test
    void testAIPerformanceDefaultConstraint() throws NoSuchMethodException {
        Method constraintMethod = AIPerformance.class.getDeclaredMethod("constraint");
        assertNotNull(constraintMethod.getDefaultValue());
        String defaultConstraint = (String) constraintMethod.getDefaultValue();
        assertFalse(defaultConstraint.isBlank(), "default constraint must not be blank");
        assertTrue(defaultConstraint.contains("complexity") || defaultConstraint.contains("Strict"),
            "default constraint should mention complexity or strict requirements");
    }

    @Test
    @AIPerformance(constraint = "O(1) required")
    void testAIPerformanceCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIPerformanceCanBeUsedOnMethods");
        AIPerformance perf = method.getAnnotation(AIPerformance.class);
        assertNull(perf); // Expected: null because SOURCE retention
    }

    @AICore(sensitivity = "High", note = "Core business logic")
    static class TestCoreClass {}

    @AIPerformance(constraint = "O(n) max")
    static class TestPerformanceClass {}

    @Test
    void testAICoreAndPerformanceCanBeAppliedToClasses() {
        assertDoesNotThrow(() -> {
            assertNotNull(TestCoreClass.class);
            assertNotNull(TestPerformanceClass.class);
        });
    }

    // -----------------------------------------------------------------------
    // @AIContract
    // -----------------------------------------------------------------------

    @Test
    void testAIContractAnnotationRetention() {
        Retention retention = AIContract.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.SOURCE, retention.value());
    }

    @Test
    void testAIContractAnnotationTargets() {
        Target target = AIContract.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(
            new ElementType[]{ElementType.TYPE, ElementType.METHOD},
            target.value()
        );
    }

    @Test
    void testAIContractDefaultReason() throws NoSuchMethodException {
        Method reasonMethod = AIContract.class.getDeclaredMethod("reason");
        String defaultReason = (String) reasonMethod.getDefaultValue();
        assertNotNull(defaultReason);
        assertFalse(defaultReason.isBlank(), "default reason must not be blank");
        assertTrue(defaultReason.contains("signature") || defaultReason.contains("contractually"),
            "default reason should mention signature or contract");
    }

    @Test
    @AIContract(reason = "Test contract")
    void testAIContractCanBeUsedOnMethods() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("testAIContractCanBeUsedOnMethods");
        AIContract contract = method.getAnnotation(AIContract.class);
        assertNull(contract); // Expected: null because SOURCE retention
    }

    @AIContract(reason = "Stable public API")
    static class TestContractClass {}

    @Test
    void testAIContractCanBeAppliedToClass() {
        assertDoesNotThrow(() -> assertNotNull(TestContractClass.class));
    }
}
