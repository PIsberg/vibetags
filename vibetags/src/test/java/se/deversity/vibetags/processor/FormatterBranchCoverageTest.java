package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.annotations.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Targets the remaining uncovered branches in annotation formatters:
 *
 * <ul>
 *   <li>AIArchitectureFormatter L24 — cannotRef.length > 0 true branch</li>
 *   <li>AIArchitectureFormatter L34 — the for-loop body (CLAUDE with cannotReference entries)</li>
 *   <li>AIArchitectureFormatter L56 — cannotRef.length > 0 true branch in LLMS_FULL</li>
 *   <li>AIArchitectureFormatter L63 — cannotRef.length > 0 in AIDER_CONVENTIONS</li>
 *   <li>AIFeatureFlagFormatter L23 — flag is empty → "(unspecified)"</li>
 *   <li>AIFeatureFlagFormatter L34 — flag is non-empty → emit flag XML element in CLAUDE</li>
 *   <li>AIIdempotentFormatter L25 — reason non-empty (summary includes reason)</li>
 *   <li>AIIdempotentFormatter L32 — reason non-empty in CLAUDE</li>
 *   <li>AIIdempotentFormatter L62 — reason non-empty in AIDER_CONVENTIONS</li>
 *   <li>AISecureFormatter L25 — aspect empty → no bracket in summary</li>
 *   <li>AISecureFormatter L62 — aspect non-empty in AIDER_CONVENTIONS</li>
 *   <li>GranularRenderer L84-85 — multi-framework AITestDriven loop</li>
 *   <li>GranularRenderer L115 — AIThreadSafe with non-empty note</li>
 *   <li>GranularRenderer L138 — AIDeprecated with replacedBy non-empty</li>
 *   <li>GranularRenderer L162 — AIObservability with traces and logs</li>
 *   <li>GranularRenderer L168 — AIRegulation with non-blank clause</li>
 *   <li>GranularRenderer L175 — AIArchitecture with non-empty cannotReference</li>
 *   <li>Various formatters default-branch (unhandled Platform)</li>
 * </ul>
 */
class FormatterBranchCoverageTest {

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
    // AIArchitectureFormatter
    // -----------------------------------------------------------------------

    @Test
    void aiArchitectureFormatter_withCannotReference_cursorPlatform_includesProhibited() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.ServiceClass");
        AIArchitecture ann = mock(AIArchitecture.class);
        when(ann.belongsTo()).thenReturn("service");
        when(ann.cannotReference()).thenReturn(new String[]{"com.example.infra"});
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        String out = sb.toString();
        assertTrue(out.contains("Prohibited"), "Non-empty cannotReference must include 'Prohibited' in CURSOR output");
        assertTrue(out.contains("com.example.infra"), "Must include the forbidden package");
    }

    @Test
    void aiArchitectureFormatter_emptyCannotReference_cursorPlatform_noProhibited() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.ServiceClass");
        AIArchitecture ann = mock(AIArchitecture.class);
        when(ann.belongsTo()).thenReturn("service");
        when(ann.cannotReference()).thenReturn(new String[0]);
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertFalse(sb.toString().contains("Prohibited"),
            "Empty cannotReference must not include 'Prohibited'");
    }

    @Test
    void aiArchitectureFormatter_withCannotReference_claudePlatform_emitsCannotReferenceXml() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.ServiceClass");
        AIArchitecture ann = mock(AIArchitecture.class);
        when(ann.belongsTo()).thenReturn("service");
        when(ann.cannotReference()).thenReturn(new String[]{"com.example.db", "com.example.infra"});
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        String out = sb.toString();
        assertTrue(out.contains("<cannot_reference>com.example.db</cannot_reference>"),
            "CLAUDE format must emit cannot_reference XML for each forbidden package");
        assertTrue(out.contains("<cannot_reference>com.example.infra</cannot_reference>"),
            "CLAUDE format must emit cannot_reference XML for second forbidden package");
    }

    @Test
    void aiArchitectureFormatter_withCannotReference_llmsFullPlatform_includesProhibited() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.Svc");
        AIArchitecture ann = mock(AIArchitecture.class);
        when(ann.belongsTo()).thenReturn("service");
        when(ann.cannotReference()).thenReturn(new String[]{"com.example.repo"});
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.LLMS_FULL);
        assertTrue(sb.toString().contains("Prohibited References"),
            "LLMS_FULL must include 'Prohibited References' when cannotReference is non-empty");
    }

    @Test
    void aiArchitectureFormatter_emptyCannotReference_llmsFullPlatform_noProhibitedLine() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.Svc");
        AIArchitecture ann = mock(AIArchitecture.class);
        when(ann.belongsTo()).thenReturn("service");
        when(ann.cannotReference()).thenReturn(new String[0]);
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.LLMS_FULL);
        assertFalse(sb.toString().contains("Prohibited References"),
            "LLMS_FULL must omit 'Prohibited References' when cannotReference is empty");
    }

    @Test
    void aiArchitectureFormatter_withCannotReference_aiderConventionsPlatform() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.Svc");
        AIArchitecture ann = mock(AIArchitecture.class);
        when(ann.belongsTo()).thenReturn("service");
        when(ann.cannotReference()).thenReturn(new String[]{"com.example.infra"});
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.AIDER_CONVENTIONS);
        assertTrue(sb.toString().contains("Cannot Reference"),
            "AIDER_CONVENTIONS must include 'Cannot Reference' when cannotReference is non-empty");
    }

    @Test
    void aiArchitectureFormatter_nullAnnotation_noOutput() {
        AIArchitectureFormatter fmt = new AIArchitectureFormatter();
        Element el = mockEl("com.example.Svc");
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertEquals(0, sb.length(), "Null annotation must produce no output");
    }

    // -----------------------------------------------------------------------
    // AIFeatureFlagFormatter
    // -----------------------------------------------------------------------

    @Test
    void aiFeatureFlagFormatter_emptyFlag_cursorPlatform_showsUnspecified() {
        AIFeatureFlagFormatter fmt = new AIFeatureFlagFormatter();
        Element el = mockEl("com.example.Gated");
        AIFeatureFlag ann = mock(AIFeatureFlag.class);
        when(ann.flag()).thenReturn("");
        when(ann.defaultValue()).thenReturn(false);
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertTrue(sb.toString().contains("(unspecified)"),
            "Empty flag must display '(unspecified)'");
    }

    @Test
    void aiFeatureFlagFormatter_nonEmptyFlag_cursorPlatform_showsQuotedFlag() {
        AIFeatureFlagFormatter fmt = new AIFeatureFlagFormatter();
        Element el = mockEl("com.example.Gated");
        AIFeatureFlag ann = mock(AIFeatureFlag.class);
        when(ann.flag()).thenReturn("my.feature.enabled");
        when(ann.defaultValue()).thenReturn(true);
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertTrue(sb.toString().contains("'my.feature.enabled'"),
            "Non-empty flag must be displayed with quotes");
    }

    @Test
    void aiFeatureFlagFormatter_nonEmptyFlag_claudePlatform_emitsFlagXml() {
        AIFeatureFlagFormatter fmt = new AIFeatureFlagFormatter();
        Element el = mockEl("com.example.Gated");
        AIFeatureFlag ann = mock(AIFeatureFlag.class);
        when(ann.flag()).thenReturn("checkout.v2");
        when(ann.defaultValue()).thenReturn(false);
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        String out = sb.toString();
        assertTrue(out.contains("<flag>checkout.v2</flag>"),
            "Non-empty flag must emit <flag> XML element in CLAUDE format");
    }

    @Test
    void aiFeatureFlagFormatter_emptyFlag_claudePlatform_noFlagXml() {
        AIFeatureFlagFormatter fmt = new AIFeatureFlagFormatter();
        Element el = mockEl("com.example.Gated");
        AIFeatureFlag ann = mock(AIFeatureFlag.class);
        when(ann.flag()).thenReturn("");
        when(ann.defaultValue()).thenReturn(false);
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        assertFalse(sb.toString().contains("<flag>"),
            "Empty flag must not emit <flag> XML element in CLAUDE format");
    }

    @Test
    void aiFeatureFlagFormatter_nullAnnotation_noOutput() {
        AIFeatureFlagFormatter fmt = new AIFeatureFlagFormatter();
        Element el = mockEl("com.example.Gated");
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertEquals(0, sb.length());
    }

    // -----------------------------------------------------------------------
    // AIIdempotentFormatter
    // -----------------------------------------------------------------------

    @Test
    void aiIdempotentFormatter_nonEmptyReason_cursorPlatform_includesReason() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("safe-to-retry");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertTrue(sb.toString().contains("safe-to-retry"),
            "Non-empty reason must appear in CURSOR output");
    }

    @Test
    void aiIdempotentFormatter_emptyReason_cursorPlatform_noReason() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertFalse(sb.toString().contains("Reason:"),
            "Empty reason must not add 'Reason:' to CURSOR output");
    }

    @Test
    void aiIdempotentFormatter_nonEmptyReason_claudePlatform_includesReasonXml() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("retryable-op");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        assertTrue(sb.toString().contains("<reason>retryable-op</reason>"),
            "Non-empty reason must emit <reason> in CLAUDE format");
    }

    @Test
    void aiIdempotentFormatter_emptyReason_claudePlatform_noReasonXml() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        assertFalse(sb.toString().contains("<reason>"),
            "Empty reason must not emit <reason> in CLAUDE format");
    }

    @Test
    void aiIdempotentFormatter_nonEmptyReason_aiderConventionsPlatform_includesReason() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("safe-to-retry");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.AIDER_CONVENTIONS);
        assertTrue(sb.toString().contains("safe-to-retry"),
            "Non-empty reason must appear in AIDER_CONVENTIONS output");
    }

    @Test
    void aiIdempotentFormatter_emptyReason_aiderConventionsPlatform_noReasonLine() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.AIDER_CONVENTIONS);
        assertFalse(sb.toString().contains("**Reason**"),
            "Empty reason must not emit Reason line in AIDER_CONVENTIONS format");
    }

    // -----------------------------------------------------------------------
    // GranularRenderer — multi-framework AITestDriven (L84-85)
    // -----------------------------------------------------------------------

    @Test
    void granularRenderer_multipleFrameworks_appendsCommaSeparated() {
        se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer();
        se.deversity.vibetags.processor.internal.AnnotationCollector collector =
            new se.deversity.vibetags.processor.internal.AnnotationCollector();

        javax.annotation.processing.RoundEnvironment re = mock(javax.annotation.processing.RoundEnvironment.class);
        Element el = mockEl("com.example.TestDrivenClass");

        AITestDriven td = mock(AITestDriven.class);
        when(td.coverageGoal()).thenReturn(80);
        when(td.testLocation()).thenReturn("src/test/java");
        when(td.mockPolicy()).thenReturn("no mocks");
        // Two frameworks → loop body with length>0 check fires (L84)
        when(td.framework()).thenReturn(new AITestDriven.Framework[]{
            AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.MOCKITO
        });
        when(el.getAnnotation(AITestDriven.class)).thenReturn(td);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AITestDriven.class);
        collector.collect(re);

        java.util.Map<javax.lang.model.element.Element, StringBuilder> result =
            renderer.renderGranular(collector);

        assertFalse(result.isEmpty(), "renderGranular must produce output for AITestDriven element");
        String out = result.values().iterator().next().toString();
        assertTrue(out.contains("JUNIT_5") || out.contains("JUnit"),
            "Output must include first framework name (JUNIT_5)");
        assertTrue(out.contains("MOCKITO") || out.contains("Mockito"),
            "Output must include second framework name (MOCKITO)");
        assertTrue(out.contains("src/test/java"), "Output must include test location");
        assertTrue(out.contains("no mocks"), "Output must include mock policy");
    }

    @Test
    void granularRenderer_aiThreadSafe_withNote_includesNote() {
        se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer();
        se.deversity.vibetags.processor.internal.AnnotationCollector collector =
            new se.deversity.vibetags.processor.internal.AnnotationCollector();

        javax.annotation.processing.RoundEnvironment re = mock(javax.annotation.processing.RoundEnvironment.class);
        Element el = mockEl("com.example.ThreadSafeClass");

        AIThreadSafe ts = mock(AIThreadSafe.class);
        when(ts.strategy()).thenReturn(AIThreadSafe.Strategy.LOCK_FREE);
        when(ts.note()).thenReturn("uses CAS operations");
        when(el.getAnnotation(AIThreadSafe.class)).thenReturn(ts);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIThreadSafe.class);
        collector.collect(re);

        java.util.Map<javax.lang.model.element.Element, StringBuilder> result =
            renderer.renderGranular(collector);
        assertFalse(result.isEmpty());
        assertTrue(result.values().iterator().next().toString().contains("uses CAS operations"),
            "Non-empty note must appear in AIThreadSafe granular output");
    }

    @Test
    void granularRenderer_aiDeprecated_withReplacedBy_includesReplacement() {
        se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer();
        se.deversity.vibetags.processor.internal.AnnotationCollector collector =
            new se.deversity.vibetags.processor.internal.AnnotationCollector();

        javax.annotation.processing.RoundEnvironment re = mock(javax.annotation.processing.RoundEnvironment.class);
        Element el = mockEl("com.example.OldService");

        AIDeprecated dep = mock(AIDeprecated.class);
        when(dep.replacedBy()).thenReturn("com.example.NewService");
        when(dep.migrationGuide()).thenReturn("use NewService.process()");
        when(dep.deadline()).thenReturn("2026-01-01");
        when(el.getAnnotation(AIDeprecated.class)).thenReturn(dep);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIDeprecated.class);
        collector.collect(re);

        java.util.Map<javax.lang.model.element.Element, StringBuilder> result =
            renderer.renderGranular(collector);
        assertFalse(result.isEmpty());
        String out = result.values().iterator().next().toString();
        assertTrue(out.contains("com.example.NewService"), "Replaced-by must appear in output");
        assertTrue(out.contains("2026-01-01"), "Deadline must appear in output");
    }

    @Test
    void granularRenderer_aiObservability_withTracesAndLogs_includesAll() {
        se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer();
        se.deversity.vibetags.processor.internal.AnnotationCollector collector =
            new se.deversity.vibetags.processor.internal.AnnotationCollector();

        javax.annotation.processing.RoundEnvironment re = mock(javax.annotation.processing.RoundEnvironment.class);
        Element el = mockEl("com.example.MonitoredService");

        AIObservability obs = mock(AIObservability.class);
        when(obs.metrics()).thenReturn(new String[]{"payment.count"});
        when(obs.traces()).thenReturn(new String[]{"checkout.span"});
        when(obs.logs()).thenReturn(new String[]{"payment.error"});
        when(obs.note()).thenReturn("critical path");
        when(el.getAnnotation(AIObservability.class)).thenReturn(obs);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIObservability.class);
        collector.collect(re);

        java.util.Map<javax.lang.model.element.Element, StringBuilder> result =
            renderer.renderGranular(collector);
        assertFalse(result.isEmpty());
        String out = result.values().iterator().next().toString();
        assertTrue(out.contains("checkout.span"), "Traces must appear in output");
        assertTrue(out.contains("payment.error"), "Logs must appear in output");
        assertTrue(out.contains("critical path"), "Note must appear in output");
    }

    @Test
    void granularRenderer_aiRegulation_withClause_includesClause() {
        se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer();
        se.deversity.vibetags.processor.internal.AnnotationCollector collector =
            new se.deversity.vibetags.processor.internal.AnnotationCollector();

        javax.annotation.processing.RoundEnvironment re = mock(javax.annotation.processing.RoundEnvironment.class);
        Element el = mockEl("com.example.GdprService");

        AIRegulation reg = mock(AIRegulation.class);
        when(reg.standard()).thenReturn("GDPR");
        when(reg.clause()).thenReturn("Art.17");
        when(reg.description()).thenReturn("right to erasure");
        when(el.getAnnotation(AIRegulation.class)).thenReturn(reg);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIRegulation.class);
        collector.collect(re);

        java.util.Map<javax.lang.model.element.Element, StringBuilder> result =
            renderer.renderGranular(collector);
        assertFalse(result.isEmpty());
        String out = result.values().iterator().next().toString();
        assertTrue(out.contains("Art.17"), "Clause must appear in output");
    }

    @Test
    void granularRenderer_aiArchitecture_withCannotReference_includesForbidden() {
        se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer();
        se.deversity.vibetags.processor.internal.AnnotationCollector collector =
            new se.deversity.vibetags.processor.internal.AnnotationCollector();

        javax.annotation.processing.RoundEnvironment re = mock(javax.annotation.processing.RoundEnvironment.class);
        Element el = mockEl("com.example.ServiceLayer");

        AIArchitecture arch = mock(AIArchitecture.class);
        when(arch.belongsTo()).thenReturn("service");
        when(arch.cannotReference()).thenReturn(new String[]{"com.example.infra", "com.example.db"});
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(arch);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIArchitecture.class);
        collector.collect(re);

        java.util.Map<javax.lang.model.element.Element, StringBuilder> result =
            renderer.renderGranular(collector);
        assertFalse(result.isEmpty());
        String out = result.values().iterator().next().toString();
        assertTrue(out.contains("Prohibited"), "cannotReference must appear as 'Prohibited' in granular output");
    }

    // -----------------------------------------------------------------------
    // Default platform branches for remaining formatters
    // -----------------------------------------------------------------------

    @Test
    void aiThreadSafeFormatter_defaultPlatform_noOutput() {
        AIThreadSafeFormatter fmt = new AIThreadSafeFormatter();
        Element el = mockEl("com.example.Safe");
        AIThreadSafe ann = mock(AIThreadSafe.class);
        when(ann.strategy()).thenReturn(AIThreadSafe.Strategy.SYNCHRONIZED);
        when(ann.note()).thenReturn("");
        when(el.getAnnotation(AIThreadSafe.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length(), "Unhandled platform must produce no output");
    }

    @Test
    void aiTestDrivenFormatter_defaultPlatform_noOutput() {
        AITestDrivenFormatter fmt = new AITestDrivenFormatter();
        Element el = mockEl("com.example.Tested");
        AITestDriven ann = mock(AITestDriven.class);
        when(ann.coverageGoal()).thenReturn(80);
        when(ann.testLocation()).thenReturn("");
        when(ann.mockPolicy()).thenReturn("");
        when(ann.framework()).thenReturn(new AITestDriven.Framework[0]);
        when(el.getAnnotation(AITestDriven.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length(), "Unhandled platform must produce no output");
    }

    @Test
    void aiLockedFormatter_defaultPlatform_noOutput() {
        AILockedFormatter fmt = new AILockedFormatter();
        Element el = mockEl("com.example.Locked");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(el.getAnnotation(AILocked.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length(), "Unhandled platform must produce no output");
    }

    @Test
    void aiContextFormatter_defaultPlatform_noOutput() {
        AIContextFormatter fmt = new AIContextFormatter();
        Element el = mockEl("com.example.Ctx");
        AIContext ann = mock(AIContext.class);
        when(ann.focus()).thenReturn("mem");
        when(ann.avoids()).thenReturn("regex");
        when(el.getAnnotation(AIContext.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length(), "Unhandled platform must produce no output");
    }

    @Test
    void aiDraftFormatter_defaultPlatform_noOutput() {
        AIDraftFormatter fmt = new AIDraftFormatter();
        Element el = mockEl("com.example.Draft");
        AIDraft ann = mock(AIDraft.class);
        when(ann.instructions()).thenReturn("do it");
        when(el.getAnnotation(AIDraft.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiCoreFormatter_defaultPlatform_noOutput() {
        AICoreFormatter fmt = new AICoreFormatter();
        Element el = mockEl("com.example.Core");
        AICore ann = mock(AICore.class);
        when(ann.sensitivity()).thenReturn("high");
        when(ann.note()).thenReturn("core");
        when(el.getAnnotation(AICore.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiContractFormatter_defaultPlatform_noOutput() {
        AIContractFormatter fmt = new AIContractFormatter();
        Element el = mockEl("com.example.Contract");
        AIContract ann = mock(AIContract.class);
        when(ann.reason()).thenReturn("frozen");
        when(el.getAnnotation(AIContract.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiDeprecatedFormatter_defaultPlatform_noOutput() {
        AIDeprecatedFormatter fmt = new AIDeprecatedFormatter();
        Element el = mockEl("com.example.Dep");
        AIDeprecated ann = mock(AIDeprecated.class);
        when(ann.replacedBy()).thenReturn("New");
        when(ann.migrationGuide()).thenReturn("use New");
        when(ann.deadline()).thenReturn("");
        when(el.getAnnotation(AIDeprecated.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiImmutableFormatter_defaultPlatform_noOutput() {
        AIImmutableFormatter fmt = new AIImmutableFormatter();
        Element el = mockEl("com.example.Imm");
        AIImmutable ann = mock(AIImmutable.class);
        when(ann.note()).thenReturn("frozen");
        when(el.getAnnotation(AIImmutable.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiObservabilityFormatter_defaultPlatform_noOutput() {
        AIObservabilityFormatter fmt = new AIObservabilityFormatter();
        Element el = mockEl("com.example.Obs");
        AIObservability ann = mock(AIObservability.class);
        when(ann.metrics()).thenReturn(new String[]{"m"});
        when(ann.traces()).thenReturn(new String[0]);
        when(ann.logs()).thenReturn(new String[0]);
        when(ann.note()).thenReturn("");
        when(el.getAnnotation(AIObservability.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiPerformanceFormatter_defaultPlatform_noOutput() {
        AIPerformanceFormatter fmt = new AIPerformanceFormatter();
        Element el = mockEl("com.example.Perf");
        AIPerformance ann = mock(AIPerformance.class);
        when(ann.constraint()).thenReturn("O(1)");
        when(el.getAnnotation(AIPerformance.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }

    @Test
    void aiRegulationFormatter_defaultPlatform_noOutput() {
        AIRegulationFormatter fmt = new AIRegulationFormatter();
        Element el = mockEl("com.example.Reg");
        AIRegulation ann = mock(AIRegulation.class);
        when(ann.standard()).thenReturn("GDPR");
        when(ann.clause()).thenReturn("");
        when(ann.description()).thenReturn("desc");
        when(el.getAnnotation(AIRegulation.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length());
    }
}
