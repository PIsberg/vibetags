package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Builds a single, human-readable guardrail-instruction block covering every collected
 * annotation. Shared by the PR-reviewer renderers (CodeRabbit, Ellipsis, PR-Agent) and the
 * Roo custom-mode renderer, which all need the same prose summary embedded into their own
 * schema (YAML block scalars, TOML multiline strings, etc.).
 *
 * <p>Formatting is delegated to the per-annotation formatters using {@link Platform#INTERPRETER}
 * (a free-text, single-line-per-element style already supported by every formatter), so the
 * block stays in lock-step with the rest of the generated guardrails without duplicating the
 * per-annotation prose here.
 */
final class GuardrailInstructionBlock {

    private GuardrailInstructionBlock() {}

    /**
     * Renders the guardrail summary as one bullet line per annotated element. Returns an empty
     * string when no annotations were collected.
     */
    static String build(AnnotationCollector collector) {
        StringBuilder sb = new StringBuilder(1024);
        Platform p = Platform.INTERPRETER;
        for (Element e : collector.locked()) FormatterRegistry.locked().format(e, sb, p);
        for (Element e : collector.context()) FormatterRegistry.context().format(e, sb, p);
        for (Element e : collector.ignore()) FormatterRegistry.ignore().format(e, sb, p);
        for (Element e : collector.audit()) FormatterRegistry.audit().format(e, sb, p);
        for (Element e : collector.draft()) FormatterRegistry.draft().format(e, sb, p);
        for (Element e : collector.privacy()) FormatterRegistry.privacy().format(e, sb, p);
        for (Element e : collector.core()) FormatterRegistry.core().format(e, sb, p);
        for (Element e : collector.performance()) FormatterRegistry.performance().format(e, sb, p);
        for (Element e : collector.contract()) FormatterRegistry.contract().format(e, sb, p);
        for (Element e : collector.testDriven()) FormatterRegistry.testDriven().format(e, sb, p);
        for (Element e : collector.threadSafe()) FormatterRegistry.threadSafe().format(e, sb, p);
        for (Element e : collector.immutable()) FormatterRegistry.immutable().format(e, sb, p);
        for (Element e : collector.deprecated()) FormatterRegistry.deprecated().format(e, sb, p);
        for (Element e : collector.observability()) FormatterRegistry.observability().format(e, sb, p);
        for (Element e : collector.regulation()) FormatterRegistry.regulation().format(e, sb, p);
        for (Element e : collector.parallelTests()) FormatterRegistry.parallelTests().format(e, sb, p);
        for (Element e : collector.legacyBridge()) FormatterRegistry.legacyBridge().format(e, sb, p);
        for (Element e : collector.architecture()) FormatterRegistry.architecture().format(e, sb, p);
        for (Element e : collector.publicApi()) FormatterRegistry.publicApi().format(e, sb, p);
        for (Element e : collector.strictExceptions()) FormatterRegistry.strictExceptions().format(e, sb, p);
        for (Element e : collector.strictTypes()) FormatterRegistry.strictTypes().format(e, sb, p);
        for (Element e : collector.internationalized()) FormatterRegistry.internationalized().format(e, sb, p);
        for (Element e : collector.strictClasspath()) FormatterRegistry.strictClasspath().format(e, sb, p);
        for (Element e : collector.schemaSafe()) FormatterRegistry.schemaSafe().format(e, sb, p);
        for (Element e : collector.idempotent()) FormatterRegistry.idempotent().format(e, sb, p);
        for (Element e : collector.featureFlag()) FormatterRegistry.featureFlag().format(e, sb, p);
        for (Element e : collector.secure()) FormatterRegistry.secure().format(e, sb, p);
        return sb.toString();
    }

    /** The guardrail summary split into non-blank lines (leading "- "/"* " bullet stripped). */
    static List<String> lines(AnnotationCollector collector) {
        List<String> out = new ArrayList<>();
        for (String line : build(collector).split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                trimmed = trimmed.substring(2).strip();
            }
            out.add(trimmed);
        }
        return out;
    }

    /** Indents every line of {@code block} by {@code spaces}, preserving blank lines as empty. */
    static String indent(String block, int spaces) {
        String pad = " ".repeat(spaces);
        StringBuilder sb = new StringBuilder(block.length() + 32);
        for (String line : block.split("\n", -1)) {
            if (line.isEmpty()) {
                sb.append("\n");
            } else {
                sb.append(pad).append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
