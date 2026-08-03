package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.WholeFileMerge;

/**
 * PlatformRenderer for generating Mentat config.
 */
public final class MentatRenderer implements PlatformRenderer {
    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder("{\n  \"_generated_by\": \"VibeTags\",\n  \"rules\": {\n");

        StringBuilder locked = new StringBuilder();
        for (TaggedElement e : model.locked()) FormatterRegistry.locked().format(e, locked, Platform.MENTAT);
        appendJsonSection(sb, "locked_files", locked);

        StringBuilder audit = new StringBuilder();
        for (TaggedElement e : model.audit()) FormatterRegistry.audit().format(e, audit, Platform.MENTAT);
        appendJsonSection(sb, "audit", audit);

        StringBuilder privacy = new StringBuilder();
        for (TaggedElement e : model.privacy()) FormatterRegistry.privacy().format(e, privacy, Platform.MENTAT);
        appendJsonSection(sb, "privacy", privacy);

        StringBuilder core = new StringBuilder();
        for (TaggedElement e : model.core()) FormatterRegistry.core().format(e, core, Platform.MENTAT);
        appendJsonSection(sb, "core", core);

        StringBuilder perf = new StringBuilder();
        for (TaggedElement e : model.performance()) FormatterRegistry.performance().format(e, perf, Platform.MENTAT);
        appendJsonSection(sb, "performance", perf);

        StringBuilder contract = new StringBuilder();
        for (TaggedElement e : model.contract()) FormatterRegistry.contract().format(e, contract, Platform.MENTAT);
        appendJsonSection(sb, "contract", contract);

        StringBuilder ignore = new StringBuilder();
        for (TaggedElement e : model.ignore()) FormatterRegistry.ignore().format(e, ignore, Platform.MENTAT);
        appendJsonSection(sb, "ignored", ignore);

        StringBuilder draft = new StringBuilder();
        for (TaggedElement e : model.draft()) FormatterRegistry.draft().format(e, draft, Platform.MENTAT);
        appendJsonSection(sb, "draft", draft);

        StringBuilder testDriven = new StringBuilder();
        for (TaggedElement e : model.testDriven()) FormatterRegistry.testDriven().format(e, testDriven, Platform.MENTAT);
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

    /**
     * The rules arrays are unioned per key across modules. Concatenating two of these documents
     * would not be JSON, and keeping only the compiling module's would publish one module's view of
     * the project — which is what {@code .mentatconfig.json} did in a reactor until #265.
     */
    @Override
    public WholeFileMerge wholeFileMerge() {
        return MERGE;
    }

    private static final WholeFileMerge MERGE = WholeFileMerge.jsonRules();

    private static void appendJsonSection(StringBuilder out, String key, StringBuilder items) {
        if (items.length() == 0) return;
        String body = items.toString();
        if (body.endsWith(",\n")) {
            body = body.substring(0, body.length() - 2) + "\n";
        }
        out.append("    \"").append(key).append("\": [\n").append(body).append("    ],\n");
    }
}
