package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import javax.annotation.processing.RoundEnvironment;
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
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment round = mock(RoundEnvironment.class);

        // Mock AICallersOnly
        Element callersOnlyElement = mock(Element.class);
        when(callersOnlyElement.toString()).thenReturn("com.example.SecureUtil");
        AICallersOnly callersOnly = mock(AICallersOnly.class);
        when(callersOnly.value()).thenReturn(new String[]{"com.example.auth.*"});
        when(callersOnlyElement.getAnnotation(AICallersOnly.class)).thenReturn(callersOnly);
        doReturn(Set.of(callersOnlyElement)).when(round).getElementsAnnotatedWith(AICallersOnly.class);

        // Mock AITemporary
        Element temporaryElement = mock(Element.class);
        when(temporaryElement.toString()).thenReturn("com.example.TempStub");
        AITemporary temporary = mock(AITemporary.class);
        when(temporary.expiresOn()).thenReturn("2026-06-30");
        when(temporary.reason()).thenReturn("Bank API downtime");
        when(temporaryElement.getAnnotation(AITemporary.class)).thenReturn(temporary);
        doReturn(Set.of(temporaryElement)).when(round).getElementsAnnotatedWith(AITemporary.class);


        collector.collect(round);

        CursorRenderer renderer = new CursorRenderer();
        RenderingContext context = new RenderingContext("TestProj", "# Header\n", Set.of("cursor"));
        String output = renderer.render(collector.model(), Platform.CURSOR, context);

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
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment round = mock(RoundEnvironment.class);

        // Mock AISandboxOnly
        Element sandboxOnlyElement = mock(Element.class);
        when(sandboxOnlyElement.toString()).thenReturn("com.example.MockGateway");
        AISandboxOnly sandboxOnly = mock(AISandboxOnly.class);
        when(sandboxOnlyElement.getAnnotation(AISandboxOnly.class)).thenReturn(sandboxOnly);
        doReturn(Set.of(sandboxOnlyElement)).when(round).getElementsAnnotatedWith(AISandboxOnly.class);

        // Mock AISecureLogging
        Element secureLoggingElement = mock(Element.class);
        when(secureLoggingElement.toString()).thenReturn("com.example.User.password");
        AISecureLogging secureLogging = mock(AISecureLogging.class);
        when(secureLogging.value()).thenReturn(AISecureLogging.MaskingPolicy.HASH);
        when(secureLoggingElement.getAnnotation(AISecureLogging.class)).thenReturn(secureLogging);
        doReturn(Set.of(secureLoggingElement)).when(round).getElementsAnnotatedWith(AISecureLogging.class);


        collector.collect(round);

        ClaudeRenderer renderer = new ClaudeRenderer();
        RenderingContext context = new RenderingContext("TestProj", "# Header\n", Set.of("claude"));
        String output = renderer.render(collector.model(), Platform.CLAUDE, context);

        assertTrue(output.contains("<sandbox_only_elements>"));
        assertTrue(output.contains("com.example.MockGateway"));

        assertTrue(output.contains("<secure_logging_elements>"));
        assertTrue(output.contains("com.example.User.password"));
        assertTrue(output.contains("<logging_policy>HASH</logging_policy>"));
    }
}
