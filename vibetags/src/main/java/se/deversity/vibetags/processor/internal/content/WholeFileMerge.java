package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * How a marker-free output file is combined across the modules of a reactor.
 *
 * <p>Most generated files carry {@code VIBETAGS-START} / {@code VIBETAGS-END} markers, and the
 * multi-module merge replaces the region between them. JSON and TOML have nowhere to put a marker
 * without becoming invalid, so those files are whole-file overwrites — and a whole-file overwrite
 * from one module is that module's view of the project, not the project's.
 *
 * <p>Before this existed, the result was worse than last-writer-wins. Sidecar bodies were only
 * stored for marker-based services, so {@code anyContributed} was permanently false for a JSON or
 * TOML output and the writer's {@code no-new-rules} guard skipped every update to an existing file:
 * whatever the first successful write produced was frozen there. On the four-module
 * {@code examples/multimodule}, {@code .mentatconfig.json} held only {@code core}'s guardrails, and
 * every subsequent build reported "no changes".
 *
 * <p>Implementations re-assemble the document from every module's rendering. They are format-aware
 * rather than generic because there is no generic answer: JSON arrays have to be unioned inside
 * their key, TOML multi-line strings have to be concatenated inside their quotes, and text
 * concatenation produces a broken file in both cases.
 *
 * <p>Parsing our own output is what makes this tractable — these documents are generated a few
 * lines above by a renderer in this same package, so their shape is known exactly. When a document
 * does not have that shape an implementation returns {@code null} rather than guessing, and the
 * caller keeps the previous behaviour.
 */
@FunctionalInterface
public interface WholeFileMerge {

    /**
     * Combines every module's rendering of one service into a single document.
     *
     * @param contributions module id → that module's complete rendered file, in output order
     * @return the merged document, or {@code null} to decline — the shape was not what this
     *         implementation expects, and the caller falls back to the compiling module's own
     *         content rather than emitting something invented
     */
    @Nullable String merge(List<Map.Entry<String, String>> contributions);

    /**
     * Unions the arrays inside each key of a {@code "rules"} object — the {@code .mentatconfig.json}
     * shape. A factory rather than a public class so the format handling stays package-private to
     * {@code content}, where the renderers that produce these documents live.
     */
    static WholeFileMerge jsonRules() {
        return JsonRulesMerge.INSTANCE;
    }

    /**
     * Unions the lines inside every {@code extra_instructions = """…"""} block — the
     * {@code .pr_agent.toml} shape.
     */
    static WholeFileMerge tomlInstructions() {
        return TomlInstructionsMerge.INSTANCE;
    }
}
