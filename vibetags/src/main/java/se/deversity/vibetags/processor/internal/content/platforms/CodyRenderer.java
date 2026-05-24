package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating Sourcegraph Cody `.cody/config.json`.
 */
public final class CodyRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        return "{\n" +
            "  \"customCommands\": [\n" +
            "    {\n" +
            "      \"name\": \"vibetags-review\",\n" +
            "      \"description\": \"Review code following VibeTags guardrails\",\n" +
            "      \"prompt\": \"Review the selected code for compliance with the project AI guardrails. Check for violations of locked files, PII handling rules, performance constraints, and security requirements as defined in the project annotations.\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"vibetags-audit\",\n" +
            "      \"description\": \"Security audit following VibeTags @AIAudit rules\",\n" +
            "      \"prompt\": \"Perform a security audit of the selected code. Check for SQL injection, thread safety issues, and other vulnerabilities flagged in @AIAudit annotations.\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";
    }
}
