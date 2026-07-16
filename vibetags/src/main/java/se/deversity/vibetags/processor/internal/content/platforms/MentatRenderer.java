package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating Mentat config.
 */
public final class MentatRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder("{\n  \"_generated_by\": \"VibeTags\",\n  \"rules\": {\n");

        StringBuilder locked = new StringBuilder();
        for (Element e : collector.locked()) FormatterRegistry.locked().format(e, locked, Platform.MENTAT);
        appendJsonSection(sb, "locked_files", locked);

        StringBuilder audit = new StringBuilder();
        for (Element e : collector.audit()) FormatterRegistry.audit().format(e, audit, Platform.MENTAT);
        appendJsonSection(sb, "audit", audit);

        StringBuilder privacy = new StringBuilder();
        for (Element e : collector.privacy()) FormatterRegistry.privacy().format(e, privacy, Platform.MENTAT);
        appendJsonSection(sb, "privacy", privacy);

        StringBuilder core = new StringBuilder();
        for (Element e : collector.core()) FormatterRegistry.core().format(e, core, Platform.MENTAT);
        appendJsonSection(sb, "core", core);

        StringBuilder perf = new StringBuilder();
        for (Element e : collector.performance()) FormatterRegistry.performance().format(e, perf, Platform.MENTAT);
        appendJsonSection(sb, "performance", perf);

        StringBuilder contract = new StringBuilder();
        for (Element e : collector.contract()) FormatterRegistry.contract().format(e, contract, Platform.MENTAT);
        appendJsonSection(sb, "contract", contract);

        StringBuilder ignore = new StringBuilder();
        for (Element e : collector.ignore()) FormatterRegistry.ignore().format(e, ignore, Platform.MENTAT);
        appendJsonSection(sb, "ignored", ignore);

        StringBuilder draft = new StringBuilder();
        for (Element e : collector.draft()) FormatterRegistry.draft().format(e, draft, Platform.MENTAT);
        appendJsonSection(sb, "draft", draft);

        StringBuilder testDriven = new StringBuilder();
        for (Element e : collector.testDriven()) FormatterRegistry.testDriven().format(e, testDriven, Platform.MENTAT);
        appendJsonSection(sb, "test_driven", testDriven);

        // The last section leaves a separator comma behind — strip it, JSON forbids
        // trailing commas and strict parsers reject the whole file.
        int len = sb.length();
        if (len >= 2 && sb.charAt(len - 2) == ',' && sb.charAt(len - 1) == '\n') {
            sb.setLength(len - 2);
            sb.append('\n');
        }

        sb.append("  }\n}\n");
        return sb.toString();
    }

    private static void appendJsonSection(StringBuilder out, String key, StringBuilder items) {
        if (items.length() == 0) return;
        String body = items.toString();
        if (body.endsWith(",\n")) {
            body = body.substring(0, body.length() - 2) + "\n";
        }
        out.append("    \"").append(key).append("\": [\n").append(body).append("    ],\n");
    }
}
