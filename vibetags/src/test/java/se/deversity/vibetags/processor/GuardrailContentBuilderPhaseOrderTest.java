package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.GuardrailContentBuilder;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which services {@link GuardrailContentBuilder#build} renders, and in what order: every opted-in
 * file service in the order it was handed in, then each implicit child of an active parent in
 * descriptor order, then {@code .aiexclude} last and only beside a Gemini or Codex file. A
 * directory service is never a key of its own.
 *
 * <p>The order is what the processor writes in, and the set is what it writes at all. The unit tests
 * beside this one ask whether a key is present; written before {@code build} was split into its
 * phases, this pins both whole so a phase run early, late or twice shows.
 */
class GuardrailContentBuilderPhaseOrderTest {

    private static List<String> keys(String... activeServices) {
        GuardrailContentBuilder builder = new GuardrailContentBuilder(new AnnotationCollector(),
            new LinkedHashSet<>(List.of(activeServices)), "Test", "");
        return List.copyOf(builder.build().contentByService.keySet());
    }

    @Test
    void optedInServicesThenImplicitChildrenThenAiExclude() {
        assertEquals(List.of("codex", "claude", "cursor", "codex_rules", "windsurf_safety", "cline_safety", "aiexclude"),
            keys("aiexclude", "windsurf_granular", "codex", "claude", "cline_granular", "cursor"));
    }

    @Test
    void theOrderOfTheOptedInServicesIsTheOrderTheyWereGiven() {
        assertEquals(List.of("cursor", "claude", "codex", "codex_rules"),
            keys("cursor", "claude", "codex"));
    }

    @Test
    void aChildIsRenderedOnlyBesideItsParent() {
        assertEquals(List.of("claude"), keys("claude"));
        assertEquals(List.of("claude", "devin_safety"), keys("claude", "devin_granular"));
    }

    @Test
    void aiExcludeNeedsGeminiOrCodexBesideIt() {
        assertEquals(List.of("gemini_md", "aiexclude"), keys("aiexclude", "gemini_md"));
        assertEquals(List.of("claude"), keys("aiexclude", "claude"));
    }

    @Test
    void aDirectoryServiceIsNeverAKeyOfItsOwn() {
        assertEquals(List.of(), keys("cursor_granular", "roo_granular"));
    }
}
