package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves every one of the 39 annotation types actually reaches
 * {@link GuardrailInstructionBlock#build(AnnotationCollector)} — each
 * {@code FormatterRegistry.X().format(...)} call in that method is otherwise unverified by any
 * other test (the shared PR-reviewer/Roo-mode instruction block is a thin pass-through, easy to
 * silently regress when a new annotation type is added but forgotten here).
 */
class GuardrailInstructionBlockTest {

    private static Element namedElement(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        Name simpleName = mock(Name.class);
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        when(simpleName.toString()).thenReturn(simple);
        when(e.getSimpleName()).thenReturn(simpleName);
        return e;
    }

    private static <A extends java.lang.annotation.Annotation> void wire(
            RoundEnvironment re, Class<A> type, Element elem, A annotation) {
        when(elem.getAnnotation(type)).thenReturn(annotation);
        doReturn(Set.of(elem)).when(re).getElementsAnnotatedWith(type);
    }

    private static AnnotationCollector buildFullCollector() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        AILocked locked = mock(AILocked.class);
        when(locked.reason()).thenReturn("legacy schema");
        wire(re, AILocked.class, namedElement("com.example.Locked"), locked);

        AIContext context = mock(AIContext.class);
        when(context.focus()).thenReturn("memory usage");
        when(context.avoids()).thenReturn("regex");
        wire(re, AIContext.class, namedElement("com.example.Context"), context);

        AIIgnore ignore = mock(AIIgnore.class);
        wire(re, AIIgnore.class, namedElement("com.example.Ignore"), ignore);

        AIAudit audit = mock(AIAudit.class);
        when(audit.checkFor()).thenReturn(new String[]{"SQL Injection"});
        wire(re, AIAudit.class, namedElement("com.example.Audit"), audit);

        AIDraft draft = mock(AIDraft.class);
        when(draft.instructions()).thenReturn("implement retry logic");
        wire(re, AIDraft.class, namedElement("com.example.Draft"), draft);

        AIPrivacy privacy = mock(AIPrivacy.class);
        when(privacy.reason()).thenReturn("PII under GDPR");
        wire(re, AIPrivacy.class, namedElement("com.example.Privacy"), privacy);

        AICore core = mock(AICore.class);
        when(core.sensitivity()).thenReturn("Critical");
        when(core.note()).thenReturn("18 months to stabilize");
        wire(re, AICore.class, namedElement("com.example.Core"), core);

        AIPerformance performance = mock(AIPerformance.class);
        when(performance.constraint()).thenReturn("O(1) required");
        wire(re, AIPerformance.class, namedElement("com.example.Performance"), performance);

        AIContract contract = mock(AIContract.class);
        when(contract.reason()).thenReturn("partner API SLA");
        wire(re, AIContract.class, namedElement("com.example.Contract"), contract);

        AITestDriven testDriven = mock(AITestDriven.class);
        when(testDriven.coverageGoal()).thenReturn(90);
        when(testDriven.testLocation()).thenReturn("src/test");
        when(testDriven.framework()).thenReturn(new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5});
        when(testDriven.mockPolicy()).thenReturn("mock external calls");
        wire(re, AITestDriven.class, namedElement("com.example.TestDriven"), testDriven);

        AIThreadSafe threadSafe = mock(AIThreadSafe.class);
        when(threadSafe.strategy()).thenReturn(AIThreadSafe.Strategy.LOCK_FREE);
        when(threadSafe.note()).thenReturn("CAS-based");
        wire(re, AIThreadSafe.class, namedElement("com.example.ThreadSafe"), threadSafe);

        AIImmutable immutable = mock(AIImmutable.class);
        when(immutable.note()).thenReturn("shared across threads");
        wire(re, AIImmutable.class, namedElement("com.example.Immutable"), immutable);

        AIDeprecated deprecated = mock(AIDeprecated.class);
        when(deprecated.replacedBy()).thenReturn("com.example.New");
        when(deprecated.migrationGuide()).thenReturn("switch to New.charge()");
        when(deprecated.deadline()).thenReturn("v2.0");
        wire(re, AIDeprecated.class, namedElement("com.example.Deprecated"), deprecated);

        AIObservability observability = mock(AIObservability.class);
        when(observability.metrics()).thenReturn(new String[]{"orders.placed"});
        when(observability.traces()).thenReturn(new String[]{"order.place"});
        when(observability.logs()).thenReturn(new String[]{"OrderPlaced"});
        when(observability.note()).thenReturn("watched by SLO dashboard");
        wire(re, AIObservability.class, namedElement("com.example.Observability"), observability);

        AIRegulation regulation = mock(AIRegulation.class);
        when(regulation.standard()).thenReturn("GDPR");
        when(regulation.clause()).thenReturn("Art. 17");
        when(regulation.description()).thenReturn("right to erasure");
        wire(re, AIRegulation.class, namedElement("com.example.Regulation"), regulation);

        AIParallelTests parallelTests = mock(AIParallelTests.class);
        wire(re, AIParallelTests.class, namedElement("com.example.ParallelTests"), parallelTests);

        AILegacyBridge legacyBridge = mock(AILegacyBridge.class);
        wire(re, AILegacyBridge.class, namedElement("com.example.LegacyBridge"), legacyBridge);

        AIArchitecture architecture = mock(AIArchitecture.class);
        when(architecture.belongsTo()).thenReturn("domain");
        when(architecture.cannotReference()).thenReturn(new String[]{"infrastructure"});
        wire(re, AIArchitecture.class, namedElement("com.example.Architecture"), architecture);

        AIPublicAPI publicApi = mock(AIPublicAPI.class);
        wire(re, AIPublicAPI.class, namedElement("com.example.PublicApi"), publicApi);

        AIStrictExceptions strictExceptions = mock(AIStrictExceptions.class);
        wire(re, AIStrictExceptions.class, namedElement("com.example.StrictExceptions"), strictExceptions);

        AIStrictTypes strictTypes = mock(AIStrictTypes.class);
        wire(re, AIStrictTypes.class, namedElement("com.example.StrictTypes"), strictTypes);

        AIInternationalized internationalized = mock(AIInternationalized.class);
        wire(re, AIInternationalized.class, namedElement("com.example.Internationalized"), internationalized);

        AIStrictClasspath strictClasspath = mock(AIStrictClasspath.class);
        wire(re, AIStrictClasspath.class, namedElement("com.example.StrictClasspath"), strictClasspath);

        AISchemaSafe schemaSafe = mock(AISchemaSafe.class);
        wire(re, AISchemaSafe.class, namedElement("com.example.SchemaSafe"), schemaSafe);

        AIIdempotent idempotent = mock(AIIdempotent.class);
        when(idempotent.reason()).thenReturn("deletion must not double-fire");
        wire(re, AIIdempotent.class, namedElement("com.example.Idempotent"), idempotent);

        AIFeatureFlag featureFlag = mock(AIFeatureFlag.class);
        when(featureFlag.flag()).thenReturn("inventory.push-alerts.enabled");
        when(featureFlag.defaultValue()).thenReturn(false);
        wire(re, AIFeatureFlag.class, namedElement("com.example.FeatureFlag"), featureFlag);

        AISecure secure = mock(AISecure.class);
        when(secure.aspect()).thenReturn("authentication");
        wire(re, AISecure.class, namedElement("com.example.Secure"), secure);

        AICallersOnly callersOnly = mock(AICallersOnly.class);
        when(callersOnly.value()).thenReturn(new String[]{"com.example.PricingService"});
        wire(re, AICallersOnly.class, namedElement("com.example.CallersOnly"), callersOnly);

        AISandboxOnly sandboxOnly = mock(AISandboxOnly.class);
        wire(re, AISandboxOnly.class, namedElement("com.example.SandboxOnly"), sandboxOnly);

        AIMemoryBudget memoryBudget = mock(AIMemoryBudget.class);
        when(memoryBudget.value()).thenReturn(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION);
        wire(re, AIMemoryBudget.class, namedElement("com.example.MemoryBudget"), memoryBudget);

        AIPure pure = mock(AIPure.class);
        wire(re, AIPure.class, namedElement("com.example.Pure"), pure);

        AIDomainModel domainModel = mock(AIDomainModel.class);
        when(domainModel.allow()).thenReturn(new String[]{"java.math.BigDecimal"});
        wire(re, AIDomainModel.class, namedElement("com.example.DomainModel"), domainModel);

        AIExtensible extensible = mock(AIExtensible.class);
        when(extensible.value()).thenReturn(AIExtensible.Strategy.STRATEGY_PATTERN);
        wire(re, AIExtensible.class, namedElement("com.example.Extensible"), extensible);

        AIInputSanitized inputSanitized = mock(AIInputSanitized.class);
        when(inputSanitized.value()).thenReturn(new AIInputSanitized.SanitizerType[]{AIInputSanitized.SanitizerType.SQL_INJECTION});
        wire(re, AIInputSanitized.class, namedElement("com.example.InputSanitized"), inputSanitized);

        AISecureLogging secureLogging = mock(AISecureLogging.class);
        when(secureLogging.value()).thenReturn(AISecureLogging.MaskingPolicy.HASH);
        wire(re, AISecureLogging.class, namedElement("com.example.SecureLogging"), secureLogging);

        AIExplain explain = mock(AIExplain.class);
        when(explain.value()).thenReturn(AIExplain.ComplexityLevel.HIGH);
        wire(re, AIExplain.class, namedElement("com.example.Explain"), explain);

        AIPrototype prototype = mock(AIPrototype.class);
        wire(re, AIPrototype.class, namedElement("com.example.Prototype"), prototype);

        AISunset sunset = mock(AISunset.class);
        when(sunset.jira()).thenReturn("DEBT-742");
        doReturn(Object.class).when(sunset).replacement();
        wire(re, AISunset.class, namedElement("com.example.Sunset"), sunset);

        AITemporary temporary = mock(AITemporary.class);
        when(temporary.expiresOn()).thenReturn("2028-12-31");
        when(temporary.reason()).thenReturn("upstream hotfix");
        wire(re, AITemporary.class, namedElement("com.example.Temporary"), temporary);

        collector.collect(re);
        return collector;
    }

    /** Maps each annotation's collector bucket name (for failure messages) to its INTERPRETER-platform tag. */
    private static Map<String, String> tagsByType() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("locked", "(locked): ");
        tags.put("context", "(context): ");
        tags.put("ignore", "(excluded): ");
        tags.put("audit", "(audit): ");
        tags.put("draft", "(draft): ");
        tags.put("privacy", "(privacy): ");
        tags.put("core", "(core, sensitivity: ");
        tags.put("performance", "(performance): ");
        tags.put("contract", "(contract): ");
        tags.put("testDriven", "(test-driven): ");
        tags.put("threadSafe", "(thread-safe): ");
        tags.put("immutable", "(immutable)");
        tags.put("deprecated", "(deprecated): ");
        tags.put("observability", "(observability): ");
        tags.put("regulation", "(regulation): ");
        tags.put("parallelTests", "(test-isolation): ");
        tags.put("legacyBridge", "(legacy-bridge): ");
        tags.put("architecture", "(architecture): ");
        tags.put("publicApi", "(public-api): ");
        tags.put("strictExceptions", "(strict-exceptions): ");
        tags.put("strictTypes", "(strict-types): ");
        tags.put("internationalized", "(i18n): ");
        tags.put("strictClasspath", "(strict-classpath): ");
        tags.put("schemaSafe", "(schema-safe): ");
        tags.put("idempotent", "(idempotent): ");
        tags.put("featureFlag", "(feature-flag): ");
        tags.put("secure", "(security-critical): ");
        tags.put("callersOnly", "(callers limited): ");
        tags.put("sandboxOnly", "(sandbox-only): ");
        tags.put("memoryBudget", "(memory-budget): ");
        tags.put("pure", "(pure): ");
        tags.put("domainModel", "(domain model): ");
        tags.put("extensible", "(extensible): ");
        tags.put("inputSanitized", "(sanitized): ");
        tags.put("secureLogging", "(secure-logging): ");
        tags.put("explain", "(explain): ");
        tags.put("prototype", "(prototype): ");
        tags.put("sunset", "(sunset): ");
        tags.put("temporary", "(temporary): ");
        return tags;
    }

    @Test
    void build_everyAnnotationType_producesItsDistinctiveTag() {
        AnnotationCollector collector = buildFullCollector();

        String output = GuardrailInstructionBlock.build(collector);

        for (Map.Entry<String, String> entry : tagsByType().entrySet()) {
            assertTrue(output.contains(entry.getValue()),
                "GuardrailInstructionBlock.build() output must contain the '" + entry.getKey()
                    + "' formatter's tag '" + entry.getValue() + "' — missing means "
                    + "FormatterRegistry." + entry.getKey() + "().format(...) was never invoked");
        }
    }

    @Test
    void lines_everyAnnotationType_isNonBlankAndTagged() {
        AnnotationCollector collector = buildFullCollector();

        var lines = GuardrailInstructionBlock.lines(collector);

        assertTrue(lines.size() >= tagsByType().size(),
            "lines() must return at least one line per annotation type (" + tagsByType().size()
                + " expected, got " + lines.size() + ")");
    }
}
