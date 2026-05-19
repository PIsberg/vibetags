package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.GuardrailContentBuilder;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GuardrailContentBuilder} targeting missed branches:
 * <ul>
 *   <li>The {@code granularActive} OR chain (L196-204): all 8 preceding conditions evaluate
 *       to false when only {@code pearai_granular} is in the active services set, covering
 *       every "false → continue evaluating" branch in the chain.</li>
 *   <li>Service-inactive false branches for windsurf/zed/mentat/sweep/interpreter across
 *       every {@code appendXxx()} method and the post-loop section appenders.</li>
 *   <li>Null v0.9.0 annotation guards ({@code if (ts == null) return;} etc.) in
 *       {@code appendThreadSafe}, {@code appendImmutable}, {@code appendDeprecated},
 *       {@code appendObservability}, and {@code appendRegulation}.</li>
 *   <li>The {@code buildSweepConfig()} empty-rules else branch (produces {@code "  []\n"}).</li>
 *   <li>The {@code appendJsonSection} early return when {@code items.length() == 0}.</li>
 * </ul>
 */
class GuardrailContentBuilderUnitTest {

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static Element namedClassElement(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name name = mock(Name.class);
        when(name.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(name);
        return e;
    }

    /**
     * Builds an {@link AnnotationCollector} with one mock element for every annotation type.
     * All annotations return reasonable non-null values so every {@code appendXxx()} method
     * runs its main body (not the null-check early return).
     */
    private static AnnotationCollector buildAllAnnotationTypes() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element locked = namedClassElement("com.example.Locked");
        AILocked lockedAnn = mock(AILocked.class);
        when(lockedAnn.reason()).thenReturn("test reason");
        when(locked.getAnnotation(AILocked.class)).thenReturn(lockedAnn);
        doReturn(Set.of(locked)).when(re).getElementsAnnotatedWith(AILocked.class);

        Element context = namedClassElement("com.example.Context");
        AIContext contextAnn = mock(AIContext.class);
        when(contextAnn.focus()).thenReturn("focus");
        when(contextAnn.avoids()).thenReturn("avoids");
        when(context.getAnnotation(AIContext.class)).thenReturn(contextAnn);
        doReturn(Set.of(context)).when(re).getElementsAnnotatedWith(AIContext.class);

        // appendIgnore does not call getAnnotation() — element only
        Element ignore = namedClassElement("com.example.Ignored");
        doReturn(Set.of(ignore)).when(re).getElementsAnnotatedWith(AIIgnore.class);

        // checkFor must be non-empty or appendAudit returns early
        Element audit = namedClassElement("com.example.Audit");
        AIAudit auditAnn = mock(AIAudit.class);
        when(auditAnn.checkFor()).thenReturn(new String[]{"SQLi"});
        when(audit.getAnnotation(AIAudit.class)).thenReturn(auditAnn);
        doReturn(Set.of(audit)).when(re).getElementsAnnotatedWith(AIAudit.class);

        Element draft = namedClassElement("com.example.Draft");
        AIDraft draftAnn = mock(AIDraft.class);
        when(draftAnn.instructions()).thenReturn("implement me");
        when(draft.getAnnotation(AIDraft.class)).thenReturn(draftAnn);
        doReturn(Set.of(draft)).when(re).getElementsAnnotatedWith(AIDraft.class);

        Element privacy = namedClassElement("com.example.Privacy");
        AIPrivacy privacyAnn = mock(AIPrivacy.class);
        when(privacyAnn.reason()).thenReturn("PII");
        when(privacy.getAnnotation(AIPrivacy.class)).thenReturn(privacyAnn);
        doReturn(Set.of(privacy)).when(re).getElementsAnnotatedWith(AIPrivacy.class);

        Element core = namedClassElement("com.example.Core");
        AICore coreAnn = mock(AICore.class);
        when(coreAnn.sensitivity()).thenReturn("high");
        when(coreAnn.note()).thenReturn("core note");
        when(core.getAnnotation(AICore.class)).thenReturn(coreAnn);
        doReturn(Set.of(core)).when(re).getElementsAnnotatedWith(AICore.class);

        Element perf = namedClassElement("com.example.Perf");
        AIPerformance perfAnn = mock(AIPerformance.class);
        when(perfAnn.constraint()).thenReturn("O(1)");
        when(perf.getAnnotation(AIPerformance.class)).thenReturn(perfAnn);
        doReturn(Set.of(perf)).when(re).getElementsAnnotatedWith(AIPerformance.class);

        Element contract = namedClassElement("com.example.Contract");
        AIContract contractAnn = mock(AIContract.class);
        when(contractAnn.reason()).thenReturn("contract reason");
        when(contract.getAnnotation(AIContract.class)).thenReturn(contractAnn);
        doReturn(Set.of(contract)).when(re).getElementsAnnotatedWith(AIContract.class);

        Element testDriven = namedClassElement("com.example.TestDriven");
        AITestDriven tdAnn = mock(AITestDriven.class);
        when(tdAnn.coverageGoal()).thenReturn(80);
        when(tdAnn.testLocation()).thenReturn("");
        when(tdAnn.mockPolicy()).thenReturn("");
        when(tdAnn.framework()).thenReturn(new AITestDriven.Framework[0]);
        when(testDriven.getAnnotation(AITestDriven.class)).thenReturn(tdAnn);
        doReturn(Set.of(testDriven)).when(re).getElementsAnnotatedWith(AITestDriven.class);

        Element threadSafe = namedClassElement("com.example.ThreadSafe");
        AIThreadSafe tsAnn = mock(AIThreadSafe.class);
        when(tsAnn.strategy()).thenReturn(AIThreadSafe.Strategy.SYNCHRONIZED);
        when(tsAnn.note()).thenReturn("");
        when(threadSafe.getAnnotation(AIThreadSafe.class)).thenReturn(tsAnn);
        doReturn(Set.of(threadSafe)).when(re).getElementsAnnotatedWith(AIThreadSafe.class);

        Element immutable = namedClassElement("com.example.Immutable");
        AIImmutable imAnn = mock(AIImmutable.class);
        when(imAnn.note()).thenReturn("");
        when(immutable.getAnnotation(AIImmutable.class)).thenReturn(imAnn);
        doReturn(Set.of(immutable)).when(re).getElementsAnnotatedWith(AIImmutable.class);

        Element deprecated = namedClassElement("com.example.Deprecated");
        AIDeprecated depAnn = mock(AIDeprecated.class);
        when(depAnn.replacedBy()).thenReturn("");
        when(depAnn.migrationGuide()).thenReturn("migrate here");
        when(depAnn.deadline()).thenReturn("");
        when(deprecated.getAnnotation(AIDeprecated.class)).thenReturn(depAnn);
        doReturn(Set.of(deprecated)).when(re).getElementsAnnotatedWith(AIDeprecated.class);

        Element obs = namedClassElement("com.example.Obs");
        AIObservability obsAnn = mock(AIObservability.class);
        when(obsAnn.metrics()).thenReturn(new String[]{"jvm.cpu"});
        when(obsAnn.traces()).thenReturn(new String[0]);
        when(obsAnn.logs()).thenReturn(new String[0]);
        when(obsAnn.note()).thenReturn("");
        when(obs.getAnnotation(AIObservability.class)).thenReturn(obsAnn);
        doReturn(Set.of(obs)).when(re).getElementsAnnotatedWith(AIObservability.class);

        Element reg = namedClassElement("com.example.Regulation");
        AIRegulation regAnn = mock(AIRegulation.class);
        when(regAnn.standard()).thenReturn("GDPR");
        when(regAnn.clause()).thenReturn("");
        when(regAnn.description()).thenReturn("handles PII");
        when(reg.getAnnotation(AIRegulation.class)).thenReturn(regAnn);
        doReturn(Set.of(reg)).when(re).getElementsAnnotatedWith(AIRegulation.class);

        collector.collect(re);
        return collector;
    }

    // -------------------------------------------------------------------------------------------
    // granularActive OR chain — covers all 8 "false → continue" branches at L196-204
    // -------------------------------------------------------------------------------------------

    @Test
    void granularActive_pearaiGranularOnly_coversOrChainFalseBranches() {
        // activeServices contains pearai_granular but NOT cursor/trae/roo/windsurf_granular,
        // continue/tabnine/amazonq/ai_rules_granular.  The OR chain evaluates each of those 8
        // as false before reaching pearai_granular=true, covering the "continue evaluating"
        // false-branch path for every preceding condition (L196-L203).
        Set<String> services = Set.of("cursor", "claude", "pearai_granular");

        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);
        Element locked = namedClassElement("com.example.Foo");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(locked.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(locked)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(collector, services, "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        assertTrue(result.contentByService.containsKey("cursor"),
                "cursor content must be present");
        assertFalse(result.elementRules.isEmpty(),
                "pearai_granular active → appendToGranular must have been called");
    }

    // -------------------------------------------------------------------------------------------
    // Service-inactive false branches (windsurf / zed / mentat / sweep / interpreter)
    // -------------------------------------------------------------------------------------------

    @Test
    void serviceInactive_noWindsurfZedMentatSweepInterpreter_falseBranchesCoveredAcrossAllAppenders() {
        // activeServices excludes windsurf, zed, mentat, sweep, interpreter.
        // With all 15 annotation-type elements present, every appendXxx() method is invoked and
        // every if (windsurfActive) / if (zedActive) / if (mentatActive) / if (sweepActive) /
        // if (interpreterActive) conditional takes the FALSE path — covering those missed branches
        // across all per-element appenders AND the post-loop section appenders.
        Set<String> services = Set.of("cursor", "claude");
        AnnotationCollector collector = buildAllAnnotationTypes();

        GuardrailContentBuilder builder = new GuardrailContentBuilder(collector, services, "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);

        assertFalse(result.contentByService.containsKey("windsurf"),
                "windsurf inactive → no windsurf entry in output");
        assertFalse(result.contentByService.containsKey("zed"),
                "zed inactive → no zed entry in output");
        assertFalse(result.contentByService.containsKey("mentat"),
                "mentat inactive → no mentat entry in output");
        assertFalse(result.contentByService.containsKey("sweep"),
                "sweep inactive → no sweep entry in output");
        assertFalse(result.contentByService.containsKey("interpreter"),
                "interpreter inactive → no interpreter entry in output");
        assertTrue(result.contentByService.containsKey("cursor"),
                "cursor must still be produced");
    }

    // -------------------------------------------------------------------------------------------
    // Null v0.9.0 annotation guards (if (ts/im/dep/obs/reg == null) return;)
    // -------------------------------------------------------------------------------------------

    @Test
    void nullAnnotations_v090_earlyReturnGuardsCovered() {
        // Mock elements return null for AIThreadSafe, AIImmutable, AIDeprecated,
        // AIObservability, AIRegulation.  Each corresponding appendXxx() method hits
        // the defensive null guard and returns early — no NPE.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element tsElem = namedClassElement("com.example.ThreadSafe");
        when(tsElem.getAnnotation(AIThreadSafe.class)).thenReturn(null);
        doReturn(Set.of(tsElem)).when(re).getElementsAnnotatedWith(AIThreadSafe.class);

        Element imElem = namedClassElement("com.example.Immutable");
        when(imElem.getAnnotation(AIImmutable.class)).thenReturn(null);
        doReturn(Set.of(imElem)).when(re).getElementsAnnotatedWith(AIImmutable.class);

        Element depElem = namedClassElement("com.example.Deprecated");
        when(depElem.getAnnotation(AIDeprecated.class)).thenReturn(null);
        doReturn(Set.of(depElem)).when(re).getElementsAnnotatedWith(AIDeprecated.class);

        Element obsElem = namedClassElement("com.example.Obs");
        when(obsElem.getAnnotation(AIObservability.class)).thenReturn(null);
        doReturn(Set.of(obsElem)).when(re).getElementsAnnotatedWith(AIObservability.class);

        Element regElem = namedClassElement("com.example.Reg");
        when(regElem.getAnnotation(AIRegulation.class)).thenReturn(null);
        doReturn(Set.of(regElem)).when(re).getElementsAnnotatedWith(AIRegulation.class);

        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor", "claude"), "Test", "");
        assertDoesNotThrow(builder::build,
                "null v0.9.0 annotations must trigger early return, not NPE");
    }

    // -------------------------------------------------------------------------------------------
    // buildSweepConfig() empty-rules else branch → produces YAML empty-array placeholder
    // -------------------------------------------------------------------------------------------

    @Test
    void sweepConfig_emptyRules_appendsYamlEmptyArrayPlaceholder() {
        // sweepActive=true with an empty collector → sweepRules remains empty →
        // buildSweepConfig() takes the else branch and appends "  []\n".
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                new AnnotationCollector(), Set.of("sweep"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        String sweep = result.contentByService.get("sweep");
        assertNotNull(sweep, "sweep must appear in contentByService when active");
        assertTrue(sweep.contains("  []\n"),
                "empty sweep rules must produce YAML empty-array placeholder");
    }

    // -------------------------------------------------------------------------------------------
    // appendJsonSection() early return when items.length() == 0
    // -------------------------------------------------------------------------------------------

    // -------------------------------------------------------------------------------------------
    // OR chain true paths (L197-203): each intermediate service as the sole granular trigger
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "trae_granular", "roo_granular", "windsurf_granular",
        "continue_granular", "tabnine_granular", "amazonq_granular", "ai_rules_granular"
    })
    void granularActive_intermediateService_coversShortCircuitTruePath(String granularService) {
        // cursor_granular is NOT present so L196 evaluates false, making each preceding condition
        // false until the named service is reached — covering the true-branch for that || operand.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);
        Element locked = namedClassElement("com.example.Foo");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(locked.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(locked)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of(granularService), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        assertFalse(result.elementRules.isEmpty(),
                granularService + " active → appendToGranular must have been called");
    }

    // -------------------------------------------------------------------------------------------
    // Null guards for pre-v0.9.0 annotations (L691, L731, L758, L785, L813, L840, L867)
    // -------------------------------------------------------------------------------------------

    @Test
    void nullAnnotations_pre090_earlyReturnGuardsCovered() {
        // Null mocks for AIAudit, AIDraft, AIPrivacy, AICore, AIPerformance, AIContract,
        // AITestDriven → each appendXxx() method hits its defensive null guard and returns early.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element auditElem = namedClassElement("com.example.Audit");
        when(auditElem.getAnnotation(AIAudit.class)).thenReturn(null);
        doReturn(Set.of(auditElem)).when(re).getElementsAnnotatedWith(AIAudit.class);

        Element draftElem = namedClassElement("com.example.Draft");
        when(draftElem.getAnnotation(AIDraft.class)).thenReturn(null);
        doReturn(Set.of(draftElem)).when(re).getElementsAnnotatedWith(AIDraft.class);

        Element privacyElem = namedClassElement("com.example.Privacy");
        when(privacyElem.getAnnotation(AIPrivacy.class)).thenReturn(null);
        doReturn(Set.of(privacyElem)).when(re).getElementsAnnotatedWith(AIPrivacy.class);

        Element coreElem = namedClassElement("com.example.Core");
        when(coreElem.getAnnotation(AICore.class)).thenReturn(null);
        doReturn(Set.of(coreElem)).when(re).getElementsAnnotatedWith(AICore.class);

        Element perfElem = namedClassElement("com.example.Perf");
        when(perfElem.getAnnotation(AIPerformance.class)).thenReturn(null);
        doReturn(Set.of(perfElem)).when(re).getElementsAnnotatedWith(AIPerformance.class);

        Element contractElem = namedClassElement("com.example.Contract");
        when(contractElem.getAnnotation(AIContract.class)).thenReturn(null);
        doReturn(Set.of(contractElem)).when(re).getElementsAnnotatedWith(AIContract.class);

        Element tdElem = namedClassElement("com.example.TestDriven");
        when(tdElem.getAnnotation(AITestDriven.class)).thenReturn(null);
        doReturn(Set.of(tdElem)).when(re).getElementsAnnotatedWith(AITestDriven.class);

        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor", "claude"), "Test", "");
        assertDoesNotThrow(builder::build,
                "null pre-v0.9.0 annotations must trigger early return, not NPE");
    }

    // -------------------------------------------------------------------------------------------
    // testDriven with windsurf/zed/mentat/sweep/interpreter active (L497-498, L910-914)
    // -------------------------------------------------------------------------------------------

    @Test
    void testDriven_allServicesActive_windsurfZedMentatSweepInterpreterBranchesTaken() {
        // windsurf, zed, mentat, sweep, interpreter all active with a testDriven element →
        // covers if (windsurfActive) L497, if (zedActive) L498, and all per-service branches
        // in appendTestDriven (L910-914).
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element tdElem = namedClassElement("com.example.Service");
        AITestDriven tdAnn = mock(AITestDriven.class);
        when(tdAnn.coverageGoal()).thenReturn(80);
        when(tdAnn.testLocation()).thenReturn("");
        when(tdAnn.mockPolicy()).thenReturn("");
        when(tdAnn.framework()).thenReturn(new AITestDriven.Framework[0]);
        when(tdElem.getAnnotation(AITestDriven.class)).thenReturn(tdAnn);
        doReturn(Set.of(tdElem)).when(re).getElementsAnnotatedWith(AITestDriven.class);

        collector.collect(re);

        Set<String> services = Set.of("cursor", "cursor_granular", "windsurf", "zed", "mentat", "sweep", "interpreter");
        GuardrailContentBuilder builder = new GuardrailContentBuilder(collector, services, "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        assertTrue(result.contentByService.containsKey("windsurf"),
                "windsurf must have output when active");
        assertTrue(result.contentByService.containsKey("sweep"),
                "sweep must have output when active");
        assertFalse(result.elementRules.isEmpty(),
                "cursor_granular active + testDriven → appendToGranular must populate elementRules");
    }

    @Test
    void testDriven_granularOnly_appendToGranularCalled() {
        // Minimal test covering the if (granularActive) true branch at L915:
        // only cursor_granular is active (no other services) with one testDriven element.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element tdElem = namedClassElement("com.example.Svc");
        AITestDriven tdAnn = mock(AITestDriven.class);
        when(tdAnn.coverageGoal()).thenReturn(80);
        when(tdAnn.testLocation()).thenReturn("");
        when(tdAnn.mockPolicy()).thenReturn("");
        when(tdAnn.framework()).thenReturn(new AITestDriven.Framework[0]);
        when(tdElem.getAnnotation(AITestDriven.class)).thenReturn(tdAnn);
        doReturn(Set.of(tdElem)).when(re).getElementsAnnotatedWith(AITestDriven.class);
        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor_granular"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        assertFalse(result.elementRules.isEmpty(),
                "granularActive=true + testDriven element → L915 true branch: elementRules must be populated");
    }

    @Test
    void testDriven_granularWithTestLocationAndMockPolicy_appendsExtraLines() {
        // L915 ternaries: (testLocation.isEmpty() ? "" : "\n- **Test Location**: " + testLocation)
        // and (mockPolicy.isEmpty() ? "" : "\n- **Mock Policy**: " + mockPolicy).
        // Non-empty values cover the false branches of both ternaries.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element tdElem = namedClassElement("com.example.Svc2");
        AITestDriven tdAnn = mock(AITestDriven.class);
        when(tdAnn.coverageGoal()).thenReturn(90);
        when(tdAnn.testLocation()).thenReturn("src/test/java/com/example");
        when(tdAnn.mockPolicy()).thenReturn("no mocks for repositories");
        when(tdAnn.framework()).thenReturn(new AITestDriven.Framework[0]);
        when(tdElem.getAnnotation(AITestDriven.class)).thenReturn(tdAnn);
        doReturn(Set.of(tdElem)).when(re).getElementsAnnotatedWith(AITestDriven.class);
        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor_granular"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        String rules = result.elementRules.values().iterator().next().toString();
        assertTrue(rules.contains("Test Location"), "non-empty testLocation must appear in granular rules");
        assertTrue(rules.contains("Mock Policy"), "non-empty mockPolicy must appear in granular rules");
    }

    // -------------------------------------------------------------------------------------------
    // testDriven with 2+ frameworks — covers the comma-append branch at L875
    // -------------------------------------------------------------------------------------------

    @Test
    void testDriven_multipleFrameworks_appendsCommasBetweenThem() {
        // Two frameworks → the second iteration hits if (frameworks.length() > 0) at L875,
        // appending a comma separator before the second framework name.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element tdElem = namedClassElement("com.example.ServiceTest");
        AITestDriven tdAnn = mock(AITestDriven.class);
        when(tdAnn.coverageGoal()).thenReturn(90);
        when(tdAnn.testLocation()).thenReturn("");
        when(tdAnn.mockPolicy()).thenReturn("");
        when(tdAnn.framework()).thenReturn(
                new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.MOCKITO});
        when(tdElem.getAnnotation(AITestDriven.class)).thenReturn(tdAnn);
        doReturn(Set.of(tdElem)).when(re).getElementsAnnotatedWith(AITestDriven.class);

        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor", "claude"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        String cursor = result.contentByService.get("cursor");
        assertNotNull(cursor);
        assertTrue(cursor.contains("JUNIT_5") && cursor.contains("MOCKITO"),
                "both frameworks must appear in cursor output separated by a comma");
    }

    // -------------------------------------------------------------------------------------------
    // appendToGranular null kind — covers the (kind != null) ? ... : "element" false path (L1117)
    // -------------------------------------------------------------------------------------------

    @Test
    void appendToGranular_nullElementKind_usesElementFallback() {
        // A method element with getKind() == null triggers the null-safe ternary at L1117:
        // (kind != null) ? kind.toString()... : "element"
        // The enclosing TypeElement is the owner so owner != element → enters the if block.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        TypeElement owner = mock(TypeElement.class);
        when(owner.toString()).thenReturn("com.example.Host");
        when(owner.getKind()).thenReturn(ElementKind.CLASS);
        Name ownerSimpleName = mock(Name.class);
        when(ownerSimpleName.toString()).thenReturn("Host");
        when(owner.getSimpleName()).thenReturn(ownerSimpleName);

        Element method = mock(Element.class);
        when(method.getKind()).thenReturn(null);
        when(method.getEnclosingElement()).thenReturn(owner);
        when(method.toString()).thenReturn("com.example.Host.doWork");
        Name methodSimpleName = mock(Name.class);
        when(methodSimpleName.toString()).thenReturn("doWork");
        when(method.getSimpleName()).thenReturn(methodSimpleName);

        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("test");
        when(method.getAnnotation(AILocked.class)).thenReturn(ann);
        doReturn(Set.of(method)).when(re).getElementsAnnotatedWith(AILocked.class);

        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor", "cursor_granular"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        StringBuilder ownerRules = result.elementRules.get(owner);
        assertNotNull(ownerRules, "granular rules for the owner type must exist");
        assertTrue(ownerRules.toString().contains("### Rules for element"),
                "null kind must fall back to 'element' label: " + ownerRules);
    }

    // -------------------------------------------------------------------------------------------
    // aiexclude without gemini/codex — covers the compound-condition false path (L1272)
    // -------------------------------------------------------------------------------------------

    @Test
    void aiexclude_withoutGeminiOrCodex_notEmittedToContentMap() {
        // L1272: if (contains("aiexclude") && (contains("gemini") || contains("codex")))
        // When aiexclude is active but neither gemini nor codex is active, the false branch
        // is taken and contentByService must NOT contain an "aiexclude" entry.
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                new AnnotationCollector(), Set.of("aiexclude"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        assertFalse(result.contentByService.containsKey("aiexclude"),
                "aiexclude without gemini or codex must not produce output");
    }

    @Test
    void aiexclude_withCodexButNotGemini_emittedToContentMap() {
        // L1272: covers the (gemini=false, codex=true) path through the || sub-expression —
        // the true branch of contains("codex") inside the compound condition.
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                new AnnotationCollector(), Set.of("aiexclude", "codex"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        assertTrue(result.contentByService.containsKey("aiexclude"),
                "aiexclude with codex (no gemini) must produce output");
    }

    // -------------------------------------------------------------------------------------------
    // observability without metrics in llmsFullActive context — covers L1063 false branch
    // -------------------------------------------------------------------------------------------

    @Test
    void observability_noMetrics_llmsFullMetricsSectionSkipped() {
        // L1063: if (metrics.length > 0) inside the llmsFullActive block.
        // An @AIObservability with only traces (no metrics) exercises the false branch so
        // no "**Metrics**" line is emitted to llms-full.txt.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element obsElem = namedClassElement("com.example.TracedService");
        AIObservability obsAnn = mock(AIObservability.class);
        when(obsAnn.metrics()).thenReturn(new String[0]);
        when(obsAnn.traces()).thenReturn(new String[]{"span.db"});
        when(obsAnn.logs()).thenReturn(new String[0]);
        when(obsAnn.note()).thenReturn("");
        when(obsElem.getAnnotation(AIObservability.class)).thenReturn(obsAnn);
        doReturn(Set.of(obsElem)).when(re).getElementsAnnotatedWith(AIObservability.class);

        collector.collect(re);

        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                collector, Set.of("cursor", "llms_full"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        String llmsFull = result.contentByService.get("llms_full");
        assertNotNull(llmsFull, "llms_full must be produced when active");
        assertFalse(llmsFull.contains("**Metrics**"),
                "no metrics → llms-full must not include a Metrics line");
        assertTrue(llmsFull.contains("span.db"),
                "llms-full must still include the trace name");
    }

    // -------------------------------------------------------------------------------------------
    // appendJsonSection() early return when items.length() == 0
    // -------------------------------------------------------------------------------------------

    @Test
    void mentatConfig_emptyCollector_appendJsonSectionSkipsAllEmptySections() {
        // mentatActive=true with an empty collector → every mentat section builder stays
        // empty → appendJsonSection(sb, key, emptyBuilder) returns early for all 9 sections.
        GuardrailContentBuilder builder = new GuardrailContentBuilder(
                new AnnotationCollector(), Set.of("mentat"), "Test", "");
        GuardrailContentBuilder.Result result = assertDoesNotThrow(builder::build);
        String mentat = result.contentByService.get("mentat");
        assertNotNull(mentat, "mentat must appear in contentByService when active");
        assertFalse(mentat.contains("\"locked_files\""),
                "empty locked section must not produce a JSON key");
        assertFalse(mentat.contains("\"audit\""),
                "empty audit section must not produce a JSON key");
        assertFalse(mentat.contains("\"draft\""),
                "empty draft section must not produce a JSON key");
    }
}
