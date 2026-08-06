package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.annotations.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests formatters that lack full coverage for all platforms.
 */
class MissingFormatterCoverageTest {

    private static Element mockEl(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name nm = mock(Name.class);
        when(nm.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(nm);
        return e;
    }

    @Test
    void testAllPlatformsForLowCoverageFormatters() {
        Element el = mockEl("com.example.TestClass");
        
        AnnotationFormatter[] formatters = {
            new AILegacyBridgeFormatter(),
            new AISchemaSafeFormatter(),
            new AIStrictClasspathFormatter(),
            new AIInternationalizedFormatter(),
            new AIStrictTypesFormatter(),
            new AIStrictExceptionsFormatter(),
            new AIPublicAPIFormatter(),
            new AIParallelTestsFormatter()
        };

        for (AnnotationFormatter formatter : formatters) {
            for (Platform platform : Platform.values()) {
                StringBuilder sb = new StringBuilder();
                formatter.format(el, sb, platform);
                assertNotNull(sb.toString(), "Output should not be null for " + formatter.getClass().getSimpleName() + " on " + platform.name());
            }
        }
    }
}
