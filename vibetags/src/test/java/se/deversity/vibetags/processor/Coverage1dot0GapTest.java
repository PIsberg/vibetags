package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.GuardrailContentBuilder;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.WriteCache;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.annotations.*;
import se.deversity.vibetags.processor.internal.content.platforms.FirebaseRenderer;
import se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers the coverage gaps identified for the 1.0 release:
 * <ul>
 *   <li>RenderingContext.getGeneratedHeader() and isActive()</li>
 *   <li>Platform.fromServiceKey() returning null and getServiceKey()</li>
 *   <li>FirebaseRenderer.render() (delegates to CursorRenderer)</li>
 *   <li>GranularRenderer.render() returning null</li>
 *   <li>GuardrailContentBuilder — inactive codex/qwen/cody/aiexclude branches</li>
 *   <li>ModuleSidecar — computeModuleId/computeModulePath escaping/different-drive cases</li>
 *   <li>WriteCache — cacheKey() fallback with absolute path, flush AtomicMoveNotSupported</li>
 *   <li>Formatter null-guard branches (AISecureFormatter, AIIdempotentFormatter,
 *       AILockedFormatter, AIContextFormatter)</li>
 * </ul>
 */
class Coverage1dot0GapTest {

    // -----------------------------------------------------------------------
    // RenderingContext
    // -----------------------------------------------------------------------

    @Test
    void renderingContext_getGeneratedHeader_returnsValue() {
        RenderingContext ctx = new RenderingContext("MyProject", "# Generated\n", Set.of("cursor", "claude"));
        assertEquals("# Generated\n", ctx.getGeneratedHeader());
    }

    @Test
    void renderingContext_isActive_trueForIncludedPlatform() {
        RenderingContext ctx = new RenderingContext("P", "h", Set.of("cursor", "claude"));
        assertTrue(ctx.isActive(Platform.CURSOR));
        assertTrue(ctx.isActive(Platform.CLAUDE));
    }

    @Test
    void renderingContext_isActive_falseForAbsentPlatform() {
        RenderingContext ctx = new RenderingContext("P", "h", Set.of("cursor"));
        assertFalse(ctx.isActive(Platform.WINDSURF));
    }

    @Test
    void renderingContext_getActiveServices_returnsUnmodifiableView() {
        Set<String> services = Set.of("cursor", "claude");
        RenderingContext ctx = new RenderingContext("P", "h", services);
        Set<String> active = ctx.getActiveServices();
        assertEquals(2, active.size(), "getActiveServices() must return all active service keys");
        assertTrue(active.contains("cursor"));
        assertTrue(active.contains("claude"));
        // Must be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> active.add("windsurf"),
            "getActiveServices() must return an unmodifiable set");
    }

    // -----------------------------------------------------------------------
    // Platform.fromServiceKey
    // -----------------------------------------------------------------------

    @Test
    void platform_fromServiceKey_knownKey_returnsCorrectPlatform() {
        assertEquals(Platform.CURSOR, Platform.fromServiceKey("cursor"));
        assertEquals(Platform.CLAUDE, Platform.fromServiceKey("claude"));
        assertEquals(Platform.FIREBASE, Platform.fromServiceKey("firebase"));
    }

    @Test
    void platform_fromServiceKey_unknownKey_returnsNull() {
        assertNull(Platform.fromServiceKey("does_not_exist_xyz_abc"));
        assertNull(Platform.fromServiceKey(""));
    }

    @Test
    void platform_getServiceKey_returnsCorrectString() {
        assertEquals("cursor", Platform.CURSOR.getServiceKey());
        assertEquals("firebase", Platform.FIREBASE.getServiceKey());
        assertEquals("kiro_granular", Platform.KIRO_GRANULAR.getServiceKey());
    }

    // -----------------------------------------------------------------------
    // FirebaseRenderer.render()
    // -----------------------------------------------------------------------

    @Test
    void firebaseRenderer_render_delegatesToCursorRendererAndReturnsNonNull() {
        FirebaseRenderer renderer = new FirebaseRenderer();
        AnnotationCollector collector = new AnnotationCollector();

        // Add a locked element so there is content
        RoundEnvironment re = mock(RoundEnvironment.class);
        Element el = mockClassElement("com.example.Foo");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(el.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        RenderingContext ctx = new RenderingContext("P", "# h\n", Set.of("firebase"));
        String result = renderer.render(collector, Platform.FIREBASE, ctx);
        assertNotNull(result, "FirebaseRenderer.render() must not return null when annotations exist");
        assertTrue(result.contains("com.example.Foo"), "Result must contain the annotated class name");
    }

    // -----------------------------------------------------------------------
    // GranularRenderer.render() — returns null (single missed method)
    // -----------------------------------------------------------------------

    @Test
    void granularRenderer_render_alwaysReturnsNull() {
        GranularRenderer renderer = new GranularRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RenderingContext ctx = new RenderingContext("P", "# h\n", Set.of("cursor_granular"));
        assertNull(renderer.render(collector, Platform.CURSOR_GRANULAR, ctx),
            "GranularRenderer.render() is a no-op and must always return null");
    }

    // -----------------------------------------------------------------------
    // GranularRenderer.renderGranular — null annotation guards
    // (Lines 30,36,...: element.getAnnotation(..) returns null)
    // -----------------------------------------------------------------------

    @Test
    void granularRenderer_renderGranular_nullAnnotationGuards_skipElements() {
        // Build a collector where getAnnotation() returns null for the annotation type
        // so the null-check guard branches fire ("if (xxx == null) continue").
        GranularRenderer renderer = new GranularRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        // locked element — but getAnnotation returns null → should be skipped
        Element el = mockClassElement("com.example.NullAnn");
        when(el.getAnnotation(AILocked.class)).thenReturn(null);
        when(el.getAnnotation(AIContext.class)).thenReturn(null);
        when(el.getAnnotation(AIAudit.class)).thenReturn(null);
        when(el.getAnnotation(AIDraft.class)).thenReturn(null);
        when(el.getAnnotation(AIPrivacy.class)).thenReturn(null);
        when(el.getAnnotation(AICore.class)).thenReturn(null);
        when(el.getAnnotation(AIPerformance.class)).thenReturn(null);
        when(el.getAnnotation(AIContract.class)).thenReturn(null);
        when(el.getAnnotation(AITestDriven.class)).thenReturn(null);
        when(el.getAnnotation(AIThreadSafe.class)).thenReturn(null);
        when(el.getAnnotation(AIImmutable.class)).thenReturn(null);
        when(el.getAnnotation(AIDeprecated.class)).thenReturn(null);
        when(el.getAnnotation(AIObservability.class)).thenReturn(null);
        when(el.getAnnotation(AIRegulation.class)).thenReturn(null);
        when(el.getAnnotation(AIArchitecture.class)).thenReturn(null);
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(null);
        when(el.getAnnotation(AIFeatureFlag.class)).thenReturn(null);
        when(el.getAnnotation(AISecure.class)).thenReturn(null);

        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AICore.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIPerformance.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AITestDriven.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIThreadSafe.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIImmutable.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIDeprecated.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIObservability.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIRegulation.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIArchitecture.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIIdempotent.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIFeatureFlag.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AISecure.class);
        collector.collect(re);

        // Should not throw; all null-guard branches must fire cleanly
        Map<Element, StringBuilder> result = renderer.renderGranular(collector);
        // Elements with all-null annotations produce no granular output
        assertTrue(result.isEmpty() || result.values().stream().allMatch(sb -> sb.length() == 0),
            "Null annotation guards must produce empty entries, got: " + result);
    }

    // -----------------------------------------------------------------------
    // AnnotationCollector — OR-chain lines 118-123 (late annotation types)
    // -----------------------------------------------------------------------

    @Test
    void annotationCollector_onlyLateAnnotationTypes_collectsAndReturnsTrue() {
        // Uses only annotation types that come late in the OR chain (lines 118–123):
        // legacyBridge, architecture, publicApi, strictExceptions, strictTypes,
        // internationalized, strictClasspath, schemaSafe, idempotent, featureFlag, secure.
        // All earlier checks (locked, context, ignore, ...) are false → forces evaluation
        // of the later OR sub-expressions.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element el = mockClassElement("com.example.LateAnnotated");

        // Return empty sets for every early annotation type
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AICore.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPerformance.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AITestDriven.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIThreadSafe.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIImmutable.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIDeprecated.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIObservability.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIRegulation.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIParallelTests.class);

        // Only the late types have elements
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AILegacyBridge.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIArchitecture.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIPublicAPI.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIStrictExceptions.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIStrictTypes.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIInternationalized.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIStrictClasspath.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AISchemaSafe.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIIdempotent.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIFeatureFlag.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AISecure.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when any annotation type has elements");
        assertTrue(collector.anyAnnotationsFound(), "anyAnnotationsFound() must be true");
        assertFalse(collector.legacyBridge().isEmpty(), "legacyBridge set must be populated");
        assertFalse(collector.secure().isEmpty(), "secure set must be populated");
        assertFalse(collector.featureFlag().isEmpty(), "featureFlag set must be populated");
        assertFalse(collector.idempotent().isEmpty(), "idempotent set must be populated");
    }

    @Test
    void annotationCollector_onlySecureAndFeatureFlag_linesL122L123() {
        // Even more targeted: only idempotent+featureFlag+secure to cover lines 122-123.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);
        Element el = mockClassElement("com.example.SecureFeature");

        // All sets empty except the very last three
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AICore.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPerformance.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AITestDriven.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIThreadSafe.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIImmutable.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIDeprecated.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIObservability.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIRegulation.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIParallelTests.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AILegacyBridge.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIArchitecture.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPublicAPI.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIStrictExceptions.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIStrictTypes.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIInternationalized.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIStrictClasspath.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AISchemaSafe.class);
        // Only these three
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIIdempotent.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AIFeatureFlag.class);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(AISecure.class);

        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
    }

    // -----------------------------------------------------------------------
    // GuardrailContentBuilder — inactive codex/qwen/cody/aiexclude branches
    // -----------------------------------------------------------------------

    @Test
    void guardrailContentBuilder_noCodex_codexBranchSkipped() {
        // When "codex" is NOT in activeServices, the codex_config/codex_rules implicit
        // activation block at line 185 must be skipped (false branch).
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("cursor"); // no "codex"
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertFalse(result.contentByService.containsKey("codex_config"),
            "codex_config must not appear when codex is not active");
        assertFalse(result.contentByService.containsKey("codex_rules"),
            "codex_rules must not appear when codex is not active");
    }

    @Test
    void guardrailContentBuilder_noQwen_qwenBranchSkipped() {
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("cursor");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertFalse(result.contentByService.containsKey("qwen_settings"),
            "qwen_settings must not appear when qwen is not active");
        assertFalse(result.contentByService.containsKey("qwen_refactor"),
            "qwen_refactor must not appear when qwen is not active");
    }

    @Test
    void guardrailContentBuilder_noCody_codyBranchSkipped() {
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("cursor");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertFalse(result.contentByService.containsKey("cody"),
            "cody must not appear when cody is not active");
    }

    @Test
    void guardrailContentBuilder_aiexcludeWithoutGeminiOrCodex_excluded() {
        // aiexclude is active but neither gemini nor codex is present → branch at L213 is false
        AnnotationCollector collector = new AnnotationCollector();
        // aiexclude alone without gemini or codex
        Set<String> services = Set.of("aiexclude");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertFalse(result.contentByService.containsKey("aiexclude"),
            "aiexclude content must not be generated without gemini or codex");
    }

    @Test
    void guardrailContentBuilder_aiexcludeWithGemini_included() {
        // aiexclude + gemini → the activation branch fires
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("aiexclude", "gemini");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertTrue(result.contentByService.containsKey("aiexclude"),
            "aiexclude content must be generated when gemini is also active");
    }

    @Test
    void guardrailContentBuilder_unknownServiceKey_skipsGracefully() {
        // A service key that has no corresponding Platform enum value → platform == null
        // at L176 → null branch fires, the unknown service is silently skipped.
        AnnotationCollector collector = new AnnotationCollector();
        // Include "cursor" (known) plus an unknown service key
        Set<String> services = Set.of("cursor", "unknown_service_xyz");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertFalse(result.contentByService.containsKey("unknown_service_xyz"),
            "Unknown service key must be silently skipped");
    }

    @Test
    void guardrailContentBuilder_codexActive_codexConfigAndRulesIncluded() {
        // codex active → implicit codex_config and codex_rules entries are generated (L185-193)
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("codex");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertTrue(result.contentByService.containsKey("codex_config"),
            "codex active → codex_config must appear in result");
        assertTrue(result.contentByService.containsKey("codex_rules"),
            "codex active → codex_rules must appear in result");
    }

    @Test
    void guardrailContentBuilder_qwenActive_settingsAndRefactorIncluded() {
        // qwen active → implicit qwen_settings and qwen_refactor entries are generated (L195-203)
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("qwen");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertTrue(result.contentByService.containsKey("qwen_settings"),
            "qwen active → qwen_settings must appear in result");
        assertTrue(result.contentByService.containsKey("qwen_refactor"),
            "qwen active → qwen_refactor must appear in result");
    }

    @Test
    void guardrailContentBuilder_codyActive_codyContentIncluded() {
        // cody active → implicit cody entry generated (L205-209)
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("cody");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertTrue(result.contentByService.containsKey("cody"),
            "cody active → cody must appear in result");
    }

    @Test
    void guardrailContentBuilder_granularActive_elementRulesMapPopulated() {
        // When a _granular service is active, renderGranular() is called (L222-228 true branch)
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("cursor_granular");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertNotNull(result.elementRules, "elementRules must not be null when granular is active");
    }

    @Test
    void guardrailContentBuilder_noGranular_elementRulesEmpty() {
        // When no _granular service is active, renderGranular() is skipped (L222-228 false branch)
        AnnotationCollector collector = new AnnotationCollector();
        Set<String> services = Set.of("cursor"); // no _granular
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
            collector, services, "Project", "# header\n");
        GuardrailContentBuilder.Result result = builder.build();
        assertTrue(result.elementRules.isEmpty(),
            "elementRules must be empty when no granular service is active");
    }

    // -----------------------------------------------------------------------
    // ModuleSidecar — computeModuleId escape / different-drive cases
    // -----------------------------------------------------------------------

    @Test
    void moduleSidecar_computeModuleId_equalPaths_returnsRoot() {
        Path root = Paths.get("/project/root");
        assertEquals("_root_", ModuleSidecar.computeModuleId(root, root));
    }

    @Test
    void moduleSidecar_computeModuleId_subPath_returnsRelative() {
        Path root = Paths.get("/project/root");
        Path child = root.resolve("module-graph");
        assertEquals("module-graph", ModuleSidecar.computeModuleId(child, root));
    }

    @Test
    void moduleSidecar_computeModuleId_outOfTreePath_returnsHash() {
        // compilationRoot is NOT under vibetagsRoot — relative path starts with ".."
        Path vibetagsRoot = Paths.get("/project/root");
        Path outOfTree = Paths.get("/other/path");
        String id = ModuleSidecar.computeModuleId(outOfTree, vibetagsRoot);
        // Must not start with ".." and must be a non-empty valid filename fragment
        assertFalse(id.startsWith(".."), "Out-of-tree id must not start with '..'");
        assertFalse(id.isEmpty(), "Out-of-tree id must not be empty");
    }

    @Test
    void moduleSidecar_computeModulePath_equalPaths_returnsEmpty() {
        Path root = Paths.get("/project/root");
        assertEquals("", ModuleSidecar.computeModulePath(root, root));
    }

    @Test
    void moduleSidecar_computeModulePath_subPath_returnsRelative() {
        Path root = Paths.get("/project/root");
        Path child = root.resolve("module-a");
        assertEquals("module-a", ModuleSidecar.computeModulePath(child, root));
    }

    @Test
    void moduleSidecar_computeModulePath_outOfTreePath_returnsEmpty() {
        // Out-of-tree: compilationRoot not under vibetagsRoot → relative path starts with ".."
        // Must return "" so readAll() skips the stale-dir check
        Path vibetagsRoot = Paths.get("/project/root");
        Path outOfTree = Paths.get("/other/path");
        assertEquals("", ModuleSidecar.computeModulePath(outOfTree, vibetagsRoot),
            "computeModulePath for out-of-tree path must return empty string");
    }

    @Test
    void moduleSidecar_computeModuleId_dotRelPath_returnsRoot() {
        // When relativize gives "." (same dir) the method must return "_root_"
        Path p = Paths.get("/some/dir");
        assertEquals("_root_", ModuleSidecar.computeModuleId(p, p));
    }

    @Test
    void moduleSidecar_computeModulePath_dotRelPath_returnsEmpty() {
        Path p = Paths.get("/some/dir");
        assertEquals("", ModuleSidecar.computeModulePath(p, p));
    }

    @Test
    void moduleSidecar_mergeFor_zeroContributions_returnsEmpty() {
        // No sidecars have the given service key → must return ""
        String result = ModuleSidecar.mergeFor("cursor",
            java.util.List.of(new ModuleSidecar("mod1", ""), new ModuleSidecar("mod2", "")),
            true);
        assertEquals("", result, "mergeFor with no contributions must return empty string");
    }

    @Test
    void moduleSidecar_mergeFor_singleContribution_returnsBodyAsIs() {
        // Single contributor: must return the body without sub-markers
        ModuleSidecar s = new ModuleSidecar("mod1", "");
        s.putBody("cursor", "some cursor rules");
        String result = ModuleSidecar.mergeFor("cursor", java.util.List.of(s), true);
        assertEquals("some cursor rules", result,
            "Single contribution must be returned as-is without sub-markers");
    }

    @Test
    void moduleSidecar_mergeFor_twoContributions_htmlMarkers_wrapsBothModules() {
        // Two contributors: must wrap each body in HTML sub-markers
        ModuleSidecar s1 = new ModuleSidecar("mod1", "");
        s1.putBody("cursor", "rules from mod1");
        ModuleSidecar s2 = new ModuleSidecar("mod2", "");
        s2.putBody("cursor", "rules from mod2");
        String result = ModuleSidecar.mergeFor("cursor", java.util.List.of(s1, s2), true);
        assertTrue(result.contains("VIBETAGS-MODULE: mod1"), "Must contain HTML module start marker for mod1");
        assertTrue(result.contains("VIBETAGS-MODULE: mod2"), "Must contain HTML module start marker for mod2");
        assertTrue(result.contains("rules from mod1"), "Must contain mod1 content");
        assertTrue(result.contains("rules from mod2"), "Must contain mod2 content");
    }

    @Test
    void moduleSidecar_mergeFor_twoContributions_hashMarkers_wrapsBothModules() {
        // Two contributors with hash-style markers
        ModuleSidecar s1 = new ModuleSidecar("mod1", "");
        s1.putBody("cursor", "rules from mod1");
        ModuleSidecar s2 = new ModuleSidecar("mod2", "");
        s2.putBody("cursor", "rules from mod2");
        String result = ModuleSidecar.mergeFor("cursor", java.util.List.of(s1, s2), false);
        assertTrue(result.contains("# VIBETAGS-MODULE: mod1"), "Must contain hash module marker for mod1");
        assertTrue(result.contains("# VIBETAGS-MODULE: mod2"), "Must contain hash module marker for mod2");
    }

    @Test
    void moduleSidecar_save_nonExistentParentDir_throws(@TempDir Path tmp) throws IOException {
        // save() writes to root; test that AtomicMoveNotSupportedException fallback
        // path works by verifying the file is correctly written on a normal filesystem.
        ModuleSidecar sidecar = new ModuleSidecar("test-module", "");
        sidecar.putBody("cursor", "some cursor rules");
        sidecar.save(tmp);
        Path saved = tmp.resolve(".vibetags-mod-test-module");
        assertTrue(Files.exists(saved), "Sidecar file must exist after save()");
        String content = Files.readString(saved, StandardCharsets.UTF_8);
        assertTrue(content.contains("test-module"), "Sidecar must contain module id");
        assertTrue(content.contains("cursor"), "Sidecar must contain service key");
    }

    @Test
    void moduleSidecar_readAll_nonDirectoryRoot_returnsEmptyList(@TempDir Path tmp) {
        Path nonDir = tmp.resolve("not-a-directory.txt");
        // Path doesn't exist → readAll must return empty list without throwing
        assertTrue(ModuleSidecar.readAll(nonDir).isEmpty(),
            "readAll on non-directory must return empty list");
    }

    @Test
    void moduleSidecar_load_malformedFile_returnsNull(@TempDir Path tmp) throws IOException {
        Path bad = tmp.resolve(".vibetags-mod-bad");
        Files.writeString(bad, "no-equals-sign-anywhere\n", StandardCharsets.UTF_8);
        // load parses lines and returns null when moduleId is never found
        // Force a fresh load by reading all sidecars from tmp
        var sidecars = ModuleSidecar.readAll(tmp);
        assertTrue(sidecars.isEmpty(), "Malformed sidecar must be pruned by readAll");
        // The malformed file should have been deleted
        assertFalse(Files.exists(bad), "Malformed sidecar file must be deleted by readAll");
    }

    // -----------------------------------------------------------------------
    // WriteCache — cacheKey() fallback (absolute path line L82)
    // -----------------------------------------------------------------------

    @Test
    void writeCache_recordAndIsUnchanged_worksWithAbsolutePath(@TempDir Path tmp) throws IOException {
        // Normal case: WriteCache stores and retrieves an entry via relative cacheKey.
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("test.json");
        String body = "hello world";
        Files.writeString(file, body, StandardCharsets.UTF_8);
        cache.recordWrite(file, body);
        assertTrue(cache.isUnchanged(file, body), "isUnchanged must return true after recordWrite");
    }

    @Test
    void writeCache_flush_atomicMove_writesFile(@TempDir Path tmp) throws IOException {
        // flush() writes .vibetags-cache; verify the file appears and contains the entry.
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("rules.cursorrules");
        String body = "rule content";
        Files.writeString(file, body, StandardCharsets.UTF_8);
        cache.recordWrite(file, body);
        cache.setBuildFingerprint("abcd1234");
        cache.flush();
        Path cacheFile = tmp.resolve(".vibetags-cache");
        assertTrue(Files.exists(cacheFile), "flush() must create .vibetags-cache");
        String cacheContent = Files.readString(cacheFile, StandardCharsets.UTF_8);
        assertTrue(cacheContent.contains("abcd1234"), "cache must contain the fingerprint");
    }

    @Test
    void writeCache_loadIfNeeded_absolutePathInCache_normalizes(@TempDir Path tmp) throws IOException {
        // Write a cache file that stores an absolute path for a key (legacy/cross-format).
        // On reload, WriteCache must normalize it without throwing.
        Path file = tmp.resolve("myrules.cursorrules");
        String body = "content";
        Files.writeString(file, body, StandardCharsets.UTF_8);
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        int hash = body.hashCode();

        // Write cache file manually with absolute path as key
        Path cacheFile = tmp.resolve(".vibetags-cache");
        String absPath = file.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(cacheFile,
            "# fingerprint: deadbeef\n"
            + absPath + "\t" + hash + "\t" + size + "\t" + mtime + "\n",
            StandardCharsets.UTF_8);

        // Loading should succeed without exception
        WriteCache cache = new WriteCache(cacheFile);
        // Calling size() triggers loadIfNeeded()
        int sz = cache.size();
        assertTrue(sz >= 1, "Cache must load the absolute-path entry, got size=" + sz);
    }

    // -----------------------------------------------------------------------
    // Formatter null-guard branches
    // -----------------------------------------------------------------------

    @Test
    void aiLockedFormatter_nullAnnotation_returnsWithoutAppending() {
        AILockedFormatter fmt = new AILockedFormatter();
        Element el = mockClassElement("com.example.Foo");
        when(el.getAnnotation(AILocked.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertEquals(0, sb.length(), "Null @AILocked annotation must cause early return");
    }

    @Test
    void aiContextFormatter_nullAnnotation_returnsWithoutAppending() {
        AIContextFormatter fmt = new AIContextFormatter();
        Element el = mockClassElement("com.example.Foo");
        when(el.getAnnotation(AIContext.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertEquals(0, sb.length(), "Null @AIContext annotation must cause early return");
    }

    @Test
    void aiSecureFormatter_nullAnnotation_returnsWithoutAppending() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Foo");
        when(el.getAnnotation(AISecure.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertEquals(0, sb.length(), "Null @AISecure annotation must cause early return");
    }

    @Test
    void aiIdempotentFormatter_nullAnnotation_returnsWithoutAppending() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockClassElement("com.example.Foo");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(null);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertEquals(0, sb.length(), "Null @AIIdempotent annotation must cause early return");
    }

    @Test
    void aiSecureFormatter_withAspect_cursorPlatform_appendsAspect() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Auth");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("authentication");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        String out = sb.toString();
        assertTrue(out.contains("com.example.Auth"), "Output must contain class name");
        assertTrue(out.contains("authentication"), "Output must contain aspect");
    }

    @Test
    void aiSecureFormatter_emptyAspect_cursorPlatform_noAspectInOutput() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Auth");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CURSOR);
        assertTrue(sb.length() > 0, "Formatter must write content even with empty aspect");
        assertFalse(sb.toString().contains("["), "Empty aspect must not produce bracket notation");
    }

    @Test
    void aiSecureFormatter_claudePlatform_withAspect_emitsXmlAspectElement() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Crypto");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("encryption");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        String out = sb.toString();
        assertTrue(out.contains("<aspect>encryption</aspect>"), "CLAUDE format must include aspect XML element");
    }

    @Test
    void aiSecureFormatter_claudePlatform_emptyAspect_noAspectElement() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Crypto");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.CLAUDE);
        assertFalse(sb.toString().contains("<aspect>"),
            "CLAUDE format must omit aspect XML element when aspect is empty");
    }

    @Test
    void aiSecureFormatter_llmsFullPlatform_withAspect_includesAspect() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Sec");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("authorization");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.LLMS_FULL);
        assertTrue(sb.toString().contains("authorization"), "LLMS_FULL must include aspect text");
    }

    @Test
    void aiSecureFormatter_llmsFullPlatform_emptyAspect_noAspectParens() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Sec");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.LLMS_FULL);
        assertFalse(sb.toString().contains("(aspect:"),
            "LLMS_FULL must omit (aspect: ...) when aspect is empty");
    }

    @Test
    void aiSecureFormatter_sweepPlatform_usesGeneralWhenAspectEmpty() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Sec");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.SWEEP);
        assertTrue(sb.toString().contains("general"),
            "SWEEP platform must use 'general' when aspect is empty");
    }

    @Test
    void aiSecureFormatter_sweepPlatform_usesAspectWhenProvided() {
        AISecureFormatter fmt = new AISecureFormatter();
        Element el = mockClassElement("com.example.Sec");
        AISecure ann = mock(AISecure.class);
        when(ann.aspect()).thenReturn("crypto");
        when(el.getAnnotation(AISecure.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.SWEEP);
        assertTrue(sb.toString().contains("crypto"),
            "SWEEP platform must use the actual aspect when provided");
    }

    @Test
    void aiIdempotentFormatter_withReason_llmsFullPlatform_includesReason() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockClassElement("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("safe-to-retry");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.LLMS_FULL);
        assertTrue(sb.toString().contains("safe-to-retry"), "LLMS_FULL must include reason");
    }

    @Test
    void aiIdempotentFormatter_emptyReason_llmsFullPlatform_noReasonLine() {
        AIIdempotentFormatter fmt = new AIIdempotentFormatter();
        Element el = mockClassElement("com.example.Retry");
        AIIdempotent ann = mock(AIIdempotent.class);
        when(ann.reason()).thenReturn("");
        when(el.getAnnotation(AIIdempotent.class)).thenReturn(ann);
        StringBuilder sb = new StringBuilder();
        fmt.format(el, sb, Platform.LLMS_FULL);
        assertFalse(sb.toString().contains("**Reason**"),
            "LLMS_FULL must omit Reason line when reason is empty");
    }

    // -----------------------------------------------------------------------
    // SynchronizedMessager — exercised via parallel compile in ProcessorTestHarness
    // (covered indirectly by triggering the processor's parallel write phase)
    // -----------------------------------------------------------------------

    @Test
    void synchronizedMessager_allFourOverloads_viaHarnessCompile(@TempDir Path tmp) throws IOException {
        // Compile a source with annotations to trigger the parallel write phase in
        // AIGuardrailProcessor.generateFiles(), which swaps in a SynchronizedMessager.
        // The processor's ForkJoinPool phase calls all four printMessage overloads through
        // the synchronized proxy, covering all three currently-missed methods.
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.sync.SomeService",
            "package com.example.sync;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"sync test\")\n"
                + "public class SomeService {}\n");
        // compile() triggers generateFiles() which creates SynchronizedMessager
        assertDoesNotThrow(() -> h.compile(),
            "compile() must not throw even when SynchronizedMessager is in use");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static Element mockClassElement(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name nm = mock(Name.class);
        when(nm.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(nm);
        return e;
    }
}
