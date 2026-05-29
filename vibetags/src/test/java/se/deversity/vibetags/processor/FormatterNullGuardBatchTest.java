package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.annotations.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Tests to cover the uncovered branches in annotation formatters:
 *
 * <p>For formatters WITH a null-guard (like AILockedFormatter, AICoreFormatter, etc.):
 * pass {@code null} annotation → covers the "annotation is null → return" true-branch.
 *
 * <p>For formatters WITHOUT a null-guard (like AIIgnoreFormatter, AILegacyBridgeFormatter, etc.):
 * the uncovered branch is {@code default: break} in the platform switch. Exercise by calling
 * with a platform not matched by any explicit case (e.g. {@code FIREBASE}, {@code KIRO_GRANULAR}).
 */
class FormatterNullGuardBatchTest {

    // A platform value not matched by most formatters → falls through to default: break
    private static final Platform DEFAULT_PLATFORM = Platform.FIREBASE;

    private static Element mockEl(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name nm = mock(Name.class);
        when(nm.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(nm);
        return e;
    }

    // -----------------------------------------------------------------------
    // AIAuditFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiAuditFormatter_nullAnnotation_noOutput(Platform p) {
        AIAuditFormatter fmt = new AIAuditFormatter();
        Element el = mockEl("com.example.Audit");
        when(el.getAnnotation(AIAudit.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIAuditFormatter: null annotation must produce no output on " + p);
    }

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE"})
    void aiAuditFormatter_emptyCheckFor_noOutput(Platform p) {
        AIAuditFormatter fmt = new AIAuditFormatter();
        Element el = mockEl("com.example.Audit");
        AIAudit ann = mock(AIAudit.class);
        when(ann.checkFor()).thenReturn(new String[0]);
        when(el.getAnnotation(AIAudit.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIAuditFormatter: empty checkFor must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // Default-branch tests: formatters without a null-guard
    // The uncovered branch is "default: break" in the platform switch.
    // Using Platform.FIREBASE (not matched by most formatters) covers it.
    // -----------------------------------------------------------------------

    @Test
    void aiIgnoreFormatter_defaultPlatform_noOutput() {
        AIIgnoreFormatter fmt = new AIIgnoreFormatter();
        Element el = mockEl("com.example.Ignored");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIIgnoreFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiLegacyBridgeFormatter_defaultPlatform_noOutput() {
        AILegacyBridgeFormatter fmt = new AILegacyBridgeFormatter();
        Element el = mockEl("com.example.Legacy");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AILegacyBridgeFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiParallelTestsFormatter_defaultPlatform_noOutput() {
        AIParallelTestsFormatter fmt = new AIParallelTestsFormatter();
        Element el = mockEl("com.example.Parallel");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIParallelTestsFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiPublicAPIFormatter_defaultPlatform_noOutput() {
        AIPublicAPIFormatter fmt = new AIPublicAPIFormatter();
        Element el = mockEl("com.example.PubApi");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIPublicAPIFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiStrictClasspathFormatter_defaultPlatform_noOutput() {
        AIStrictClasspathFormatter fmt = new AIStrictClasspathFormatter();
        Element el = mockEl("com.example.Classpath");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIStrictClasspathFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiStrictExceptionsFormatter_defaultPlatform_noOutput() {
        AIStrictExceptionsFormatter fmt = new AIStrictExceptionsFormatter();
        Element el = mockEl("com.example.Exc");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIStrictExceptionsFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiStrictTypesFormatter_defaultPlatform_noOutput() {
        AIStrictTypesFormatter fmt = new AIStrictTypesFormatter();
        Element el = mockEl("com.example.Types");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIStrictTypesFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiInternationalizedFormatter_defaultPlatform_noOutput() {
        AIInternationalizedFormatter fmt = new AIInternationalizedFormatter();
        Element el = mockEl("com.example.I18n");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIInternationalizedFormatter: unhandled platform must produce no output");
    }

    // -----------------------------------------------------------------------
    // AICoreFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiCoreFormatter_nullAnnotation_noOutput(Platform p) {
        AICoreFormatter fmt = new AICoreFormatter();
        Element el = mockEl("com.example.Core");
        when(el.getAnnotation(AICore.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AICoreFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIContractFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiContractFormatter_nullAnnotation_noOutput(Platform p) {
        AIContractFormatter fmt = new AIContractFormatter();
        Element el = mockEl("com.example.Contract");
        when(el.getAnnotation(AIContract.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIContractFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIDraftFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiDraftFormatter_nullAnnotation_noOutput(Platform p) {
        AIDraftFormatter fmt = new AIDraftFormatter();
        Element el = mockEl("com.example.Draft");
        when(el.getAnnotation(AIDraft.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIDraftFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIDeprecatedFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiDeprecatedFormatter_nullAnnotation_noOutput(Platform p) {
        AIDeprecatedFormatter fmt = new AIDeprecatedFormatter();
        Element el = mockEl("com.example.Deprecated");
        when(el.getAnnotation(AIDeprecated.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIDeprecatedFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIImmutableFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiImmutableFormatter_nullAnnotation_noOutput(Platform p) {
        AIImmutableFormatter fmt = new AIImmutableFormatter();
        Element el = mockEl("com.example.Immutable");
        when(el.getAnnotation(AIImmutable.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIImmutableFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIObservabilityFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiObservabilityFormatter_nullAnnotation_noOutput(Platform p) {
        AIObservabilityFormatter fmt = new AIObservabilityFormatter();
        Element el = mockEl("com.example.Obs");
        when(el.getAnnotation(AIObservability.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIObservabilityFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIPerformanceFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiPerformanceFormatter_nullAnnotation_noOutput(Platform p) {
        AIPerformanceFormatter fmt = new AIPerformanceFormatter();
        Element el = mockEl("com.example.Perf");
        when(el.getAnnotation(AIPerformance.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIPerformanceFormatter: null annotation must produce no output on " + p);
    }

    // -----------------------------------------------------------------------
    // AIRegulationFormatter
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CURSOR", "CLAUDE", "CODEX"})
    void aiRegulationFormatter_nullAnnotation_noOutput(Platform p) {
        AIRegulationFormatter fmt = new AIRegulationFormatter();
        Element el = mockEl("com.example.Reg");
        when(el.getAnnotation(AIRegulation.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, p);
        assertEquals(0, sb.length(), "AIRegulationFormatter: null annotation must produce no output on " + p);
    }

    @Test
    void aiSchemaSafeFormatter_defaultPlatform_noOutput() {
        AISchemaSafeFormatter fmt = new AISchemaSafeFormatter();
        Element el = mockEl("com.example.Schema");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AISchemaSafeFormatter: unhandled platform must produce no output");
    }

    @Test
    void aiInternationalizedFormatter_defaultPlatform_noOutput2() {
        AIInternationalizedFormatter fmt = new AIInternationalizedFormatter();
        Element el = mockEl("com.example.I18n");
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, DEFAULT_PLATFORM);
        assertEquals(0, sb.length(), "AIInternationalizedFormatter: unhandled platform must produce no output (2)");
    }
}
