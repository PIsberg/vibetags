package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.platforms.*;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end rendering and formatters tests for the 12 new annotations.
 */
class NewAnnotationsV4EndToEndTest {

    @Test
    void testNewAnnotationsRendering_Cursor() {
        AnnotationCollector collector = mock(AnnotationCollector.class);

        // Mock AICallersOnly
        Element callersOnlyElement = mock(Element.class);
        when(callersOnlyElement.toString()).thenReturn("com.example.SecureUtil");
        AICallersOnly callersOnly = mock(AICallersOnly.class);
        when(callersOnly.value()).thenReturn(new String[]{"com.example.auth.*"});
        when(callersOnlyElement.getAnnotation(AICallersOnly.class)).thenReturn(callersOnly);
        doReturn(Set.of(callersOnlyElement)).when(collector).callersOnly();

        // Mock AITemporary
        Element temporaryElement = mock(Element.class);
        when(temporaryElement.toString()).thenReturn("com.example.TempStub");
        AITemporary temporary = mock(AITemporary.class);
        when(temporary.expiresOn()).thenReturn("2026-06-30");
        when(temporary.reason()).thenReturn("Bank API downtime");
        when(temporaryElement.getAnnotation(AITemporary.class)).thenReturn(temporary);
        doReturn(Set.of(temporaryElement)).when(collector).temporary();

        // Mock remaining collectors as empty to avoid NPEs
        doReturn(Set.of()).when(collector).locked();
        doReturn(Set.of()).when(collector).context();
        doReturn(Set.of()).when(collector).audit();
        doReturn(Set.of()).when(collector).ignore();
        doReturn(Set.of()).when(collector).draft();
        doReturn(Set.of()).when(collector).privacy();
        doReturn(Set.of()).when(collector).core();
        doReturn(Set.of()).when(collector).performance();
        doReturn(Set.of()).when(collector).contract();
        doReturn(Set.of()).when(collector).testDriven();
        doReturn(Set.of()).when(collector).threadSafe();
        doReturn(Set.of()).when(collector).immutable();
        doReturn(Set.of()).when(collector).deprecated();
        doReturn(Set.of()).when(collector).observability();
        doReturn(Set.of()).when(collector).regulation();
        doReturn(Set.of()).when(collector).parallelTests();
        doReturn(Set.of()).when(collector).legacyBridge();
        doReturn(Set.of()).when(collector).architecture();
        doReturn(Set.of()).when(collector).publicApi();
        doReturn(Set.of()).when(collector).strictExceptions();
        doReturn(Set.of()).when(collector).strictTypes();
        doReturn(Set.of()).when(collector).internationalized();
        doReturn(Set.of()).when(collector).strictClasspath();
        doReturn(Set.of()).when(collector).schemaSafe();
        doReturn(Set.of()).when(collector).idempotent();
        doReturn(Set.of()).when(collector).featureFlag();
        doReturn(Set.of()).when(collector).secure();
        doReturn(Set.of()).when(collector).sandboxOnly();
        doReturn(Set.of()).when(collector).memoryBudget();
        doReturn(Set.of()).when(collector).pure();
        doReturn(Set.of()).when(collector).domainModel();
        doReturn(Set.of()).when(collector).extensible();
        doReturn(Set.of()).when(collector).inputSanitized();
        doReturn(Set.of()).when(collector).secureLogging();
        doReturn(Set.of()).when(collector).explain();
        doReturn(Set.of()).when(collector).prototype();
        doReturn(Set.of()).when(collector).sunset();

        CursorRenderer renderer = new CursorRenderer();
        RenderingContext context = new RenderingContext("TestProj", "# Header\n", Set.of("cursor"));
        String output = renderer.render(collector, Platform.CURSOR, context);

        assertTrue(output.contains("ACCESS & CALLS LIMITATIONS"));
        assertTrue(output.contains("com.example.SecureUtil"));
        assertTrue(output.contains("Only callable by: [com.example.auth.*]"));

        assertTrue(output.contains("TEMPORARY CODE WORKAROUNDS"));
        assertTrue(output.contains("com.example.TempStub"));
        assertTrue(output.contains("Expires on: 2026-06-30"));
        assertTrue(output.contains("Bank API downtime"));
    }

    @Test
    void testNewAnnotationsRendering_Claude() {
        AnnotationCollector collector = mock(AnnotationCollector.class);

        // Mock AISandboxOnly
        Element sandboxOnlyElement = mock(Element.class);
        when(sandboxOnlyElement.toString()).thenReturn("com.example.MockGateway");
        AISandboxOnly sandboxOnly = mock(AISandboxOnly.class);
        when(sandboxOnlyElement.getAnnotation(AISandboxOnly.class)).thenReturn(sandboxOnly);
        doReturn(Set.of(sandboxOnlyElement)).when(collector).sandboxOnly();

        // Mock AISecureLogging
        Element secureLoggingElement = mock(Element.class);
        when(secureLoggingElement.toString()).thenReturn("com.example.User.password");
        AISecureLogging secureLogging = mock(AISecureLogging.class);
        when(secureLogging.value()).thenReturn(AISecureLogging.MaskingPolicy.HASH);
        when(secureLoggingElement.getAnnotation(AISecureLogging.class)).thenReturn(secureLogging);
        doReturn(Set.of(secureLoggingElement)).when(collector).secureLogging();

        // Mock remaining collectors as empty to avoid NPEs
        doReturn(Set.of()).when(collector).locked();
        doReturn(Set.of()).when(collector).context();
        doReturn(Set.of()).when(collector).audit();
        doReturn(Set.of()).when(collector).ignore();
        doReturn(Set.of()).when(collector).draft();
        doReturn(Set.of()).when(collector).privacy();
        doReturn(Set.of()).when(collector).core();
        doReturn(Set.of()).when(collector).performance();
        doReturn(Set.of()).when(collector).contract();
        doReturn(Set.of()).when(collector).testDriven();
        doReturn(Set.of()).when(collector).threadSafe();
        doReturn(Set.of()).when(collector).immutable();
        doReturn(Set.of()).when(collector).deprecated();
        doReturn(Set.of()).when(collector).observability();
        doReturn(Set.of()).when(collector).regulation();
        doReturn(Set.of()).when(collector).parallelTests();
        doReturn(Set.of()).when(collector).legacyBridge();
        doReturn(Set.of()).when(collector).architecture();
        doReturn(Set.of()).when(collector).publicApi();
        doReturn(Set.of()).when(collector).strictExceptions();
        doReturn(Set.of()).when(collector).strictTypes();
        doReturn(Set.of()).when(collector).internationalized();
        doReturn(Set.of()).when(collector).strictClasspath();
        doReturn(Set.of()).when(collector).schemaSafe();
        doReturn(Set.of()).when(collector).idempotent();
        doReturn(Set.of()).when(collector).featureFlag();
        doReturn(Set.of()).when(collector).secure();
        doReturn(Set.of()).when(collector).callersOnly();
        doReturn(Set.of()).when(collector).memoryBudget();
        doReturn(Set.of()).when(collector).pure();
        doReturn(Set.of()).when(collector).domainModel();
        doReturn(Set.of()).when(collector).extensible();
        doReturn(Set.of()).when(collector).inputSanitized();
        doReturn(Set.of()).when(collector).explain();
        doReturn(Set.of()).when(collector).prototype();
        doReturn(Set.of()).when(collector).sunset();
        doReturn(Set.of()).when(collector).temporary();

        ClaudeRenderer renderer = new ClaudeRenderer();
        RenderingContext context = new RenderingContext("TestProj", "# Header\n", Set.of("claude"));
        String output = renderer.render(collector, Platform.CLAUDE, context);

        assertTrue(output.contains("<sandbox_only_elements>"));
        assertTrue(output.contains("com.example.MockGateway"));

        assertTrue(output.contains("<secure_logging_elements>"));
        assertTrue(output.contains("com.example.User.password"));
        assertTrue(output.contains("<logging_policy>HASH</logging_policy>"));
    }
}
