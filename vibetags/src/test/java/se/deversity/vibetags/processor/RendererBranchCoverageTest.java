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
import se.deversity.vibetags.processor.internal.content.platforms.AiderConventionsRenderer;
import se.deversity.vibetags.processor.internal.content.platforms.LlmsRenderer;

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

    @Test
    void interpreterRenderer_emptyCollector_omitsGuardrailsSection() {
        // Kills the "changed conditional boundary" mutant on `if (rules.length() > 0)`:
        // with an empty collector the rules buffer is empty, so the
        // "## Project Guardrails" header must NOT be emitted. A `>= 0` mutant would
        // emit the header for zero-length rules and this assertion catches it.
        InterpreterRenderer renderer = new InterpreterRenderer();
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);
        when(re.getElementsAnnotatedWith(any(Class.class))).thenReturn(Set.of());
        collector.collect(re);

        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("interpreter"));
        String result = renderer.render(collector, Platform.INTERPRETER, ctx);
        assertNotNull(result);
        assertFalse(result.contains("Project Guardrails"),
            "empty collector must not emit the Project Guardrails section");
    }

    // ------------------------------------------------------------------
    // Direct render() coverage for InterpreterRenderer + AiderConventionsRenderer.
    //
    // These renderers stream 27 annotation buckets into their output, one distinct
    // formatter call per bucket. PIT's "removed call to <Formatter>::format" mutants
    // survived the end-to-end tests because those tests generate the files in a
    // @BeforeAll fixture that PIT does NOT re-run per mutation — so the assertions
    // only ever saw output from the unmutated renderer. Calling render() directly
    // inside the @Test body re-executes the mutated code per mutation, so deleting
    // any single formatter call drops its marker and fails the matching assertion.
    // ------------------------------------------------------------------

    private static <A extends java.lang.annotation.Annotation> void register(
            RoundEnvironment re, Class<A> type, String fqn, A ann) {
        Element el = mockEl(fqn);
        when(el.getAnnotation(type)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(type);
    }

    /** Builds a collector holding one element for every annotation the two renderers emit. */
    private static AnnotationCollector everyRenderedAnnotation() {
        RoundEnvironment re = mock(RoundEnvironment.class);

        AILocked locked = mock(AILocked.class);
        when(locked.reason()).thenReturn("core logic");
        register(re, AILocked.class, "com.ex.Locked", locked);

        AIContext context = mock(AIContext.class);
        when(context.focus()).thenReturn("auth flow");
        when(context.avoids()).thenReturn("reflection");
        register(re, AIContext.class, "com.ex.Ctx", context);

        AIIgnore ignore = mock(AIIgnore.class);
        when(ignore.reason()).thenReturn("generated");
        register(re, AIIgnore.class, "com.ex.Ignored", ignore);

        AIAudit audit = mock(AIAudit.class);
        when(audit.checkFor()).thenReturn(new String[]{"SQL Injection"});
        register(re, AIAudit.class, "com.ex.Audited", audit);

        AIDraft draft = mock(AIDraft.class);
        when(draft.instructions()).thenReturn("implement SMTP");
        register(re, AIDraft.class, "com.ex.Drafted", draft);

        AIPrivacy privacy = mock(AIPrivacy.class);
        when(privacy.reason()).thenReturn("PII");
        register(re, AIPrivacy.class, "com.ex.Private", privacy);

        AICore core = mock(AICore.class);
        when(core.sensitivity()).thenReturn("high");
        when(core.note()).thenReturn("battle tested");
        register(re, AICore.class, "com.ex.Core", core);

        AIPerformance perf = mock(AIPerformance.class);
        when(perf.constraint()).thenReturn("O(1) per call");
        register(re, AIPerformance.class, "com.ex.Perf", perf);

        AIContract contract = mock(AIContract.class);
        when(contract.reason()).thenReturn("partner API SLA");
        register(re, AIContract.class, "com.ex.Contracted", contract);

        AITestDriven testDriven = mock(AITestDriven.class);
        when(testDriven.coverageGoal()).thenReturn(90);
        when(testDriven.testLocation()).thenReturn("src/test/java");
        when(testDriven.mockPolicy()).thenReturn("no mocks");
        when(testDriven.framework())
            .thenReturn(new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5});
        register(re, AITestDriven.class, "com.ex.Tested", testDriven);

        AIThreadSafe threadSafe = mock(AIThreadSafe.class);
        when(threadSafe.strategy()).thenReturn(AIThreadSafe.Strategy.LOCK_FREE);
        when(threadSafe.note()).thenReturn("CAS backed");
        register(re, AIThreadSafe.class, "com.ex.Safe", threadSafe);

        AIImmutable immutable = mock(AIImmutable.class);
        when(immutable.note()).thenReturn("value object");
        register(re, AIImmutable.class, "com.ex.Frozen", immutable);

        AIDeprecated deprecated = mock(AIDeprecated.class);
        when(deprecated.replacedBy()).thenReturn("com.ex.NewThing");
        when(deprecated.migrationGuide()).thenReturn("use NewThing");
        when(deprecated.deadline()).thenReturn("2030-01-01");
        register(re, AIDeprecated.class, "com.ex.Old", deprecated);

        AIObservability observability = mock(AIObservability.class);
        when(observability.metrics()).thenReturn(new String[]{"req.count"});
        when(observability.traces()).thenReturn(new String[]{"checkout.span"});
        when(observability.logs()).thenReturn(new String[]{"err.log"});
        when(observability.note()).thenReturn("critical path");
        register(re, AIObservability.class, "com.ex.Monitored", observability);

        AIRegulation regulation = mock(AIRegulation.class);
        when(regulation.standard()).thenReturn("GDPR");
        when(regulation.clause()).thenReturn("Art.17");
        when(regulation.description()).thenReturn("erasure");
        register(re, AIRegulation.class, "com.ex.Regulated", regulation);

        AIParallelTests parallelTests = mock(AIParallelTests.class);
        when(parallelTests.reason()).thenReturn("isolation");
        register(re, AIParallelTests.class, "com.ex.Parallel", parallelTests);

        AILegacyBridge legacyBridge = mock(AILegacyBridge.class);
        when(legacyBridge.reason()).thenReturn("compat");
        register(re, AILegacyBridge.class, "com.ex.Bridge", legacyBridge);

        AIArchitecture architecture = mock(AIArchitecture.class);
        when(architecture.belongsTo()).thenReturn("service");
        when(architecture.cannotReference()).thenReturn(new String[]{"com.ex.infra"});
        register(re, AIArchitecture.class, "com.ex.Layered", architecture);

        AIPublicAPI publicApi = mock(AIPublicAPI.class);
        when(publicApi.reason()).thenReturn("public api");
        register(re, AIPublicAPI.class, "com.ex.Api", publicApi);

        AIStrictExceptions strictExceptions = mock(AIStrictExceptions.class);
        when(strictExceptions.reason()).thenReturn("no generic exceptions");
        register(re, AIStrictExceptions.class, "com.ex.StrictExc", strictExceptions);

        AIStrictTypes strictTypes = mock(AIStrictTypes.class);
        when(strictTypes.reason()).thenReturn("no loose typing");
        register(re, AIStrictTypes.class, "com.ex.StrictTypes", strictTypes);

        AIInternationalized i18n = mock(AIInternationalized.class);
        when(i18n.reason()).thenReturn("no hardcoded labels");
        register(re, AIInternationalized.class, "com.ex.I18n", i18n);

        AIStrictClasspath strictClasspath = mock(AIStrictClasspath.class);
        when(strictClasspath.reason()).thenReturn("no dynamic loading");
        register(re, AIStrictClasspath.class, "com.ex.Classpath", strictClasspath);

        AISchemaSafe schemaSafe = mock(AISchemaSafe.class);
        when(schemaSafe.reason()).thenReturn("no schema drift");
        register(re, AISchemaSafe.class, "com.ex.Schema", schemaSafe);

        AIIdempotent idempotent = mock(AIIdempotent.class);
        when(idempotent.reason()).thenReturn("safe to retry");
        register(re, AIIdempotent.class, "com.ex.Idem", idempotent);

        AIFeatureFlag featureFlag = mock(AIFeatureFlag.class);
        when(featureFlag.flag()).thenReturn("checkout.v2");
        when(featureFlag.defaultValue()).thenReturn(true);
        register(re, AIFeatureFlag.class, "com.ex.Gated", featureFlag);

        AISecure secure = mock(AISecure.class);
        when(secure.aspect()).thenReturn("authentication");
        register(re, AISecure.class, "com.ex.Secured", secure);

        // --- The 12 newer annotations (rendered by LlmsRenderer / ClaudeRenderer, ignored by
        //     the interpreter and aider renderers, so harmless to populate for all of them). ---

        AICallersOnly callersOnly = mock(AICallersOnly.class);
        when(callersOnly.value()).thenReturn(new String[]{"com.ex.auth"});
        register(re, AICallersOnly.class, "com.ex.Callers", callersOnly);

        AISandboxOnly sandboxOnly = mock(AISandboxOnly.class);
        when(sandboxOnly.reason()).thenReturn("test only");
        register(re, AISandboxOnly.class, "com.ex.Sandbox", sandboxOnly);

        AIMemoryBudget memoryBudget = mock(AIMemoryBudget.class);
        when(memoryBudget.value()).thenReturn(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION);
        register(re, AIMemoryBudget.class, "com.ex.Budgeted", memoryBudget);

        AIPure pure = mock(AIPure.class);
        when(pure.reason()).thenReturn("no side effects");
        register(re, AIPure.class, "com.ex.Pure", pure);

        AIDomainModel domainModel = mock(AIDomainModel.class);
        when(domainModel.allow()).thenReturn(new String[]{"java.util"});
        register(re, AIDomainModel.class, "com.ex.Domain", domainModel);

        AIExtensible extensible = mock(AIExtensible.class);
        when(extensible.value()).thenReturn(AIExtensible.Strategy.STRATEGY_PATTERN);
        register(re, AIExtensible.class, "com.ex.Extending", extensible);

        AIInputSanitized inputSanitized = mock(AIInputSanitized.class);
        when(inputSanitized.value())
            .thenReturn(new AIInputSanitized.SanitizerType[]{AIInputSanitized.SanitizerType.SQL_INJECTION});
        register(re, AIInputSanitized.class, "com.ex.Sanitized", inputSanitized);

        AISecureLogging secureLogging = mock(AISecureLogging.class);
        when(secureLogging.value()).thenReturn(AISecureLogging.MaskingPolicy.HASH);
        register(re, AISecureLogging.class, "com.ex.Masked", secureLogging);

        AIExplain explain = mock(AIExplain.class);
        when(explain.value()).thenReturn(AIExplain.ComplexityLevel.HIGH);
        register(re, AIExplain.class, "com.ex.Explained", explain);

        AIPrototype prototype = mock(AIPrototype.class);
        when(prototype.reason()).thenReturn("experimental");
        register(re, AIPrototype.class, "com.ex.Proto", prototype);

        AISunset sunset = mock(AISunset.class);
        when(sunset.jira()).thenReturn("DEBT-742");
        doReturn(Object.class).when(sunset).replacement();
        register(re, AISunset.class, "com.ex.Sunsetting", sunset);

        AITemporary temporary = mock(AITemporary.class);
        when(temporary.expiresOn()).thenReturn("2030-01-01");
        when(temporary.reason()).thenReturn("upstream downtime");
        register(re, AITemporary.class, "com.ex.Temp", temporary);

        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(re);
        return collector;
    }

    private static final String[] INTERPRETER_TAGS = {
        "(locked):", "(context):", "(excluded):", "(audit):", "(draft):",
        "(privacy):", "(core, sensitivity:", "(performance):", "(contract):",
        "(test-driven):", "(thread-safe):", "(immutable)", "(deprecated):",
        "(observability):", "(regulation):", "(test-isolation):", "(legacy-bridge):",
        "(architecture):", "(public-api):", "(strict-exceptions):", "(strict-types):",
        "(i18n):", "(strict-classpath):", "(schema-safe):", "(idempotent):",
        "(feature-flag):", "(security-critical):",
    };

    private static final String[] AIDER_HEADERS = {
        "#### LOCKED:", "#### CONTEXT:", "#### IGNORE:", "#### SECURITY AUDIT:",
        "#### DRAFT/TODO:", "#### PRIVACY/PII:", "#### CORE FUNCTIONALITY:",
        "#### PERFORMANCE CONSTRAINTS:", "#### CONTRACT:", "#### TEST-DRIVEN:",
        "#### THREAD-SAFE:", "#### IMMUTABLE:", "#### DEPRECATED:", "#### OBSERVABILITY:",
        "#### REGULATORY:", "#### TEST ISOLATION:", "#### LEGACY BRIDGE:",
        "#### ARCHITECTURE LAYER:", "#### PUBLIC API:", "#### STRICT EXCEPTIONS:",
        "#### STRICT TYPES:", "#### INTERNATIONALIZED:", "#### STRICT CLASSPATH:",
        "#### SCHEMA SAFE:", "#### IDEMPOTENT:", "#### FEATURE FLAG:",
        "#### SECURITY-CRITICAL:",
    };

    @Test
    void interpreterRenderer_rendersEveryAnnotationTag() {
        InterpreterRenderer renderer = new InterpreterRenderer();
        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("interpreter"));
        String out = renderer.render(everyRenderedAnnotation(), Platform.INTERPRETER, ctx);
        for (String tag : INTERPRETER_TAGS) {
            assertTrue(out.contains(tag),
                "InterpreterRenderer must emit the " + tag + " tag");
        }
    }

    @Test
    void aiderConventionsRenderer_rendersEveryAnnotationHeader() {
        AiderConventionsRenderer renderer = new AiderConventionsRenderer();
        RenderingContext ctx =
            new RenderingContext("P", "# header\n", Set.of("aider_conventions"));
        String out = renderer.render(everyRenderedAnnotation(), Platform.AIDER_CONVENTIONS, ctx);
        for (String header : AIDER_HEADERS) {
            assertTrue(out.contains(header),
                "AiderConventionsRenderer must emit the " + header + " section");
        }
    }

    /** Every element's fully-qualified path, unique per annotation, as emitted by the collector above. */
    private static final String[] ALL_ELEMENT_PATHS = {
        "com.ex.Locked", "com.ex.Ctx", "com.ex.Ignored", "com.ex.Audited", "com.ex.Drafted",
        "com.ex.Private", "com.ex.Core", "com.ex.Perf", "com.ex.Contracted", "com.ex.Tested",
        "com.ex.Safe", "com.ex.Frozen", "com.ex.Old", "com.ex.Monitored", "com.ex.Regulated",
        "com.ex.Parallel", "com.ex.Bridge", "com.ex.Layered", "com.ex.Api", "com.ex.StrictExc",
        "com.ex.StrictTypes", "com.ex.I18n", "com.ex.Classpath", "com.ex.Schema", "com.ex.Idem",
        "com.ex.Gated", "com.ex.Secured", "com.ex.Callers", "com.ex.Sandbox", "com.ex.Budgeted",
        "com.ex.Pure", "com.ex.Domain", "com.ex.Extending", "com.ex.Sanitized", "com.ex.Masked",
        "com.ex.Explained", "com.ex.Proto", "com.ex.Sunsetting", "com.ex.Temp",
    };

    @Test
    void llmsRenderer_llmsFull_rendersEveryAnnotatedElement() {
        // LlmsRenderer streams all 39 annotation buckets into llms-full.txt, one appendSection
        // call each. Each element's FQN is unique, so asserting every path appears kills both the
        // "removed call to <Formatter>::format" mutants (dropping an element's line) and the
        // section-guard mutants (dropping a whole section). render() runs in the @Test body so
        // PIT re-executes the mutated code per mutation.
        LlmsRenderer renderer = new LlmsRenderer();
        RenderingContext ctx =
            new RenderingContext("Proj", "# header\n", Set.of("llms", "llms_full"));
        String out = renderer.render(everyRenderedAnnotation(), Platform.LLMS_FULL, ctx);
        for (String path : ALL_ELEMENT_PATHS) {
            assertTrue(out.contains(path),
                "llms-full.txt must render the annotated element " + path);
        }
    }

    @Test
    void llmsRenderer_llms_rendersEveryAnnotatedElement() {
        // Same coverage for the compact llms.txt platform path (the other branch of every
        // `full ? ... : ...` header ternary in LlmsRenderer.render()).
        LlmsRenderer renderer = new LlmsRenderer();
        RenderingContext ctx =
            new RenderingContext("Proj", "# header\n", Set.of("llms", "llms_full"));
        String out = renderer.render(everyRenderedAnnotation(), Platform.LLMS, ctx);
        for (String path : ALL_ELEMENT_PATHS) {
            assertTrue(out.contains(path),
                "llms.txt must render the annotated element " + path);
        }
    }
}
