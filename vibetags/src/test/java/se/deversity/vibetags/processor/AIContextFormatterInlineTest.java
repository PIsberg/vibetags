package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.model.TaggedElement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The joins inside {@code AIContextFormatter} when both {@code focus} and {@code avoids} are set.
 *
 * <p>Six of the formatter's seven mutants survived the mutation report: every conditional in the
 * inline join ({@code "Focus - X. Avoid - Y"}) and the Codex prose join ({@code "Focus on X.
 * Avoid Y."}) could be flipped without a failure, because the existing fixtures only ever render
 * one member at a time or none. The separator between the two halves exists precisely for the
 * both-set case, so that is the case pinned here.
 */
class AIContextFormatterInlineTest {

    private static final TaggedElement BOTH_SET = GuardrailModels.element(AIContext.class);

    private static String render(Platform platform) {
        StringBuilder sb = new StringBuilder();
        FormatterRegistry.context().format(BOTH_SET, sb, platform);
        return sb.toString();
    }

    @Test
    @DisplayName("the inline form separates Focus and Avoid with a full stop")
    void inlineFormSeparatesTheHalves() {
        String llms = render(Platform.LLMS);
        assertTrue(llms.contains("Focus - "), "the focus half went missing: " + llms);
        assertTrue(llms.contains(". Avoid - "),
            "with both members set the halves must be joined by '. ': " + llms);
    }

    @Test
    @DisplayName("the Codex prose form keeps both sentences, in order, separated by a space")
    void codexProseKeepsBothSentences() {
        String codex = render(Platform.CODEX);
        assertTrue(codex.contains("Focus on "), "the focus sentence went missing: " + codex);
        assertTrue(codex.contains(". Avoid "),
            "with both members set the sentences must read as prose, '. ' between them: " + codex);
        assertFalse(codex.contains(".Avoid"),
            "the space after the focus sentence is part of the committed AGENTS.md wording: " + codex);
    }
}
