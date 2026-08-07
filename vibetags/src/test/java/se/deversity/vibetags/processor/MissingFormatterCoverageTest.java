package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.annotations.*;
import se.deversity.vibetags.processor.model.TaggedElement;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests formatters that lack full coverage for all platforms.
 */
class MissingFormatterCoverageTest {

    private static TaggedElement mockEl(String fqn) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return TaggedElement.builder(fqn)
                .names(fqn, simple, simple, fqn.replace('.', '-'))
                .build();
    }

    @Test
    void testAllPlatformsForLowCoverageFormatters() {
        TaggedElement el = mockEl("com.example.TestClass");

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
