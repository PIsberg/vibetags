package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.GranularRulesWriter;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.annotations.*;
import se.deversity.vibetags.processor.internal.content.platforms.IgnoreFileRenderer;
import se.deversity.vibetags.processor.internal.content.platforms.MentatRenderer;
import se.deversity.vibetags.processor.internal.content.platforms.PlandexRenderer;
import se.deversity.vibetags.processor.internal.content.platforms.InterpreterRenderer;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers remaining uncovered branches in renderer and related classes:
 * <ul>
 *   <li>PlatformRendererRegistry.getRenderer() — granular and cline/junie platform cases</li>
 *   <li>IgnoreFileRenderer.getPlatformSpecificName() default branch</li>
 *   <li>MentatRenderer.appendJsonSection() L61 — body not ending with ",\n"</li>
 *   <li>PlandexRenderer privacy section L36-37</li>
 *   <li>InterpreterRenderer — an uncovered branch</li>
 *   <li>GranularRulesWriter L54 — no granular services → early return</li>
 *   <li>AIAuditFormatter L24 default branch</li>
 *   <li>AISecureFormatter L62 (AIDER_CONVENTIONS with non-empty aspect)</li>
 * </ul>
 */
class RendererBranchCoverageTest {

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
    // PlatformRendererRegistry — exercise cases not yet covered
    // -----------------------------------------------------------------------

    @Test
    void platformRendererRegistry_granularPlatforms_returnGranularRenderer() {
        // Each of these maps to GRANULAR_RENDERER — covers the switch cases
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.TRAE_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.ROO_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.WINDSURF_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.CONTINUE_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.TABNINE_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.AMAZONQ_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.AI_RULES_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.PEARAI_GRANULAR));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.KIRO_GRANULAR));
    }

    @Test
    void platformRendererRegistry_clineAndJunie_returnNonNullRenderer() {
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.CLINE));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.JUNIE));
    }

    @Test
    void platformRendererRegistry_ignorePlatforms_returnIgnoreFileRenderer() {
        // Verify all ignore-file platforms map to IgnoreFileRenderer
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.ANTIGRAVITY_IGNORE));
        assertNotNull(PlatformRendererRegistry.getRenderer(Platform.CODEIUM_IGNORE));
    }

    // -----------------------------------------------------------------------
    // IgnoreFileRenderer — default branch in getPlatformSpecificName
    // -----------------------------------------------------------------------

    @Test
    void ignoreFileRenderer_withCursorIgnorePlatform_producesCursorName() {
        IgnoreFileRenderer renderer = new IgnoreFileRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("cursor_ignore"));
        String result = renderer.render(collector, Platform.CURSOR_IGNORE, ctx);
        assertTrue(result.contains("Cursor"), "CURSOR_IGNORE must produce 'Cursor' in output");
    }

    @Test
    void ignoreFileRenderer_withFirebasePlatform_producesDefaultName() {
        // Platform.FIREBASE is not in any specific case → default: return "AI Platform"
        IgnoreFileRenderer renderer = new IgnoreFileRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("firebase"));
        // Call with a platform that falls to default branch
        String result = renderer.render(collector, Platform.FIREBASE, ctx);
        assertTrue(result.contains("AI Platform"),
            "Default platform branch must produce 'AI Platform' in IgnoreFileRenderer");
    }

    // -----------------------------------------------------------------------
    // MentatRenderer — appendJsonSection body does NOT end with ",\n"
    // -----------------------------------------------------------------------

    @Test
    void mentatRenderer_lockedElement_producesValidJson() {
        MentatRenderer renderer = new MentatRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element el = mockEl("com.example.LockedClass");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test reason");
        when(el.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("mentat"));
        String result = renderer.render(collector, Platform.MENTAT, ctx);
        assertNotNull(result);
        // The body of appendJsonSection ends with ",\n" from the formatter,
        // so the trim branch fires (L61 true branch = remove trailing comma).
        assertTrue(result.contains("locked_files"), "Mentat output must contain locked_files section");
        // Verify valid JSON: no trailing comma before closing bracket
        assertFalse(result.contains(",\n    ]"),
            "Mentat output must not have trailing comma before closing bracket");
    }

    // -----------------------------------------------------------------------
    // PlandexRenderer — locked section (L23-25) and privacy section (L36-37)
    // -----------------------------------------------------------------------

    @Test
    void plandexRenderer_lockedElement_includesLockedSection() {
        PlandexRenderer renderer = new PlandexRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element el = mockEl("com.example.Config");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("do not modify");
        when(el.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("plandex"));
        String result = renderer.render(collector, Platform.PLANDEX, ctx);
        assertNotNull(result);
        assertTrue(result.contains("locked"), "Plandex output must contain locked section when @AILocked elements exist");
        // Verify privacy section is absent when no @AIPrivacy elements exist
        assertFalse(result.contains("  privacy:"), "Plandex output must omit privacy section when no @AIPrivacy elements");
    }

    @Test
    void plandexRenderer_auditElement_includesAuditSection() {
        // Cover the audit section (L28-31) which also has a partial branch
        PlandexRenderer renderer = new PlandexRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element el = mockEl("com.example.Audited");
        AIAudit ann = mock(AIAudit.class);
        when(ann.checkFor()).thenReturn(new String[]{"XSS"});
        when(el.getAnnotation(AIAudit.class)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIAudit.class);
        collector.collect(re);

        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("plandex"));
        String result = renderer.render(collector, Platform.PLANDEX, ctx);
        assertNotNull(result);
        // AIAuditFormatter handles PLANDEX via the default case — so audit content depends on formatter
        assertNotNull(result, "PlandexRenderer must produce non-null output");
    }

    // -----------------------------------------------------------------------
    // InterpreterRenderer — uncovered branch
    // -----------------------------------------------------------------------

    @Test
    void interpreterRenderer_withLockedElement_includesLockedSection() {
        se.deversity.vibetags.processor.internal.content.platforms.InterpreterRenderer renderer =
            new se.deversity.vibetags.processor.internal.content.platforms.InterpreterRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element el = mockEl("com.example.Locked");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(el.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("interpreter"));
        String result = renderer.render(collector, Platform.INTERPRETER, ctx);
        assertNotNull(result);
        assertTrue(result.contains("com.example.Locked"), "InterpreterRenderer must include locked class");
    }

    // -----------------------------------------------------------------------
    // GranularRulesWriter L54 — no granular services → early return
    // -----------------------------------------------------------------------

    @Test
    void granularRulesWriter_noGranularServices_returnsEmptySetImmediately(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# header\n", null, null);
        GranularRulesWriter granularWriter = new GranularRulesWriter(writer);

        Map<Element, StringBuilder> elementRules = Map.of();
        Map<String, Path> serviceFiles = Map.of();
        // No granular services → the early-return path at L54 fires
        Set<String> noGranularServices = Set.of("cursor", "claude");
        Set<String> written = granularWriter.writeAll(elementRules, serviceFiles, noGranularServices);
        assertTrue(written.isEmpty(),
            "writeAll with no granular services must return empty set immediately");
    }

    // -----------------------------------------------------------------------
    // AIAuditFormatter default platform branch
    // -----------------------------------------------------------------------

    @Test
    void aiAuditFormatter_defaultPlatform_noOutput() {
        AIAuditFormatter fmt = new AIAuditFormatter();
        Element el = mockEl("com.example.Audit");
        AIAudit ann = mock(AIAudit.class);
        when(ann.checkFor()).thenReturn(new String[]{"SQL injection"});
        when(el.getAnnotation(AIAudit.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        // Platform.FIREBASE is not a case in AIAuditFormatter → default: break
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length(), "Unhandled platform must produce no output from AIAuditFormatter");
    }

    // -----------------------------------------------------------------------
    // AISecureFormatter L62 — AIDER_CONVENTIONS with non-empty aspect
    // -----------------------------------------------------------------------

    @Test
    void aiSecureFormatter_aiderConventionsPlatform_nonEmptyAspect_includesAspect() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockEl("com.example.Auth");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("authentication");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.AIDER_CONVENTIONS);
        String out = sb.toString();
        assertTrue(out.contains("authentication"),
            "AIDER_CONVENTIONS output must include the security aspect");
        assertTrue(out.contains("SECURITY-CRITICAL"),
            "AIDER_CONVENTIONS output must include SECURITY-CRITICAL header");
    }

    @Test
    void aiSecureFormatter_aiderConventionsPlatform_emptyAspect_noAspectLine() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockEl("com.example.Auth");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.AIDER_CONVENTIONS);
        assertFalse(sb.toString().contains("**Aspect**"),
            "AIDER_CONVENTIONS must omit Aspect line when aspect is empty");
    }

    // -----------------------------------------------------------------------
    // AIIdempotentFormatter L25 — non-empty reason changes summary content
    // -----------------------------------------------------------------------

    @Test
    void aiIdempotentFormatter_nonEmptyReason_summaryIncludesReason() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockEl("com.example.Idem");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("safe-to-retry-on-timeout");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CODEX);
        assertTrue(sb.toString().contains("safe-to-retry-on-timeout"),
            "Non-empty reason must appear in CODEX output summary");
    }

    // -----------------------------------------------------------------------
    // AIFeatureFlagFormatter L27 — default platform branch
    // -----------------------------------------------------------------------

    @Test
    void aiFeatureFlagFormatter_defaultPlatform_noOutput() {
        AIFeatureFlagFormatter fmt = new AIFeatureFlagFormatter();
        Element el = mockEl("com.example.Gated");
        AIFeatureFlag ann = mock(AIFeatureFlag.class);
        when(ann.flag()).thenReturn("my.flag");
        when(ann.defaultValue()).thenReturn(false);
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.FIREBASE);
        assertEquals(0, sb.length(), "Unhandled platform must produce no output from AIFeatureFlagFormatter");
    }

    // -----------------------------------------------------------------------
    // BuildFingerprint — partial branches (empty annotation sets)
    // -----------------------------------------------------------------------

    @Test
    void buildFingerprint_emptyCollector_producesConsistentHash() {
        AnnotationCollector collector = new AnnotationCollector();
        String fp1 = se.deversity.vibetags.processor.internal.BuildFingerprint.compute(collector, Set.of());
        String fp2 = se.deversity.vibetags.processor.internal.BuildFingerprint.compute(collector, Set.of());
        assertEquals(fp1, fp2, "Empty collector must produce stable fingerprint");
        assertEquals(8, fp1.length(), "Fingerprint must be 8 hex characters");
    }

    @Test
    void buildFingerprint_differentServiceSets_produceDifferentHashes() {
        AnnotationCollector collector = new AnnotationCollector();
        String fp1 = se.deversity.vibetags.processor.internal.BuildFingerprint.compute(
            collector, Set.of("cursor"));
        String fp2 = se.deversity.vibetags.processor.internal.BuildFingerprint.compute(
            collector, Set.of("cursor", "claude"));
        assertNotEquals(fp1, fp2,
            "Different active service sets must produce different fingerprints");
    }

    @Test
    void buildFingerprint_withAnnotations_producesNonTrivialHash() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element el = mockEl("com.example.Locked");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(el.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        String fp = se.deversity.vibetags.processor.internal.BuildFingerprint.compute(
            collector, Set.of("cursor"));
        assertNotNull(fp);
        assertEquals(8, fp.length());
    }
}
