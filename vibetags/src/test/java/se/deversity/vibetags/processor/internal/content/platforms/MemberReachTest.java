package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every text-valued member an author can set must reach at least one generated file.
 *
 * <p>The general form of issue #506, which was one member of one shape: {@code reason()} was
 * accepted by nineteen annotations and discarded by the granular renderer for twelve of them, so
 * the file an agent read carried the annotation's canned sentence and nothing the author wrote.
 * Nothing failed, because the member is optional and the file was still produced. The only thing
 * missing was the part that was specific to the project.
 *
 * <p>That defect is invisible to every other renderer test: they assert what a formatter produces,
 * and a formatter that never mentions a member produces nothing to assert against. This asks the
 * complementary question, of the union of every platform file plus the per-element rule files, and
 * derives it from {@link GuardrailAnnotations#ALL} and each annotation's own declared members, so
 * annotation 45 and its members are covered on the day they land.
 *
 * <p>Scoped to {@code String} and {@code String[]} because those are the members whose value is
 * prose the author wrote and nobody else can reconstruct. {@code GuardrailModels} answers them with
 * {@code fixture-<member>}, which is what makes the search exact rather than a guess at wording.
 */
class MemberReachTest {

    private static final RenderingContext CONTEXT = new RenderingContext(
        "Test Project", "# Generated Header\n",
        Set.of("llms", "llms_full", "mentat", "pr_agent", "cody", "qwen_settings",
               "codex_config", "sweep", "plandex", "interpreter", "aider_conventions"));

    @Test
    @DisplayName("every String-valued annotation member reaches some generated file")
    void everyTextMemberReachesAGeneratedFile() {
        String rendered = everythingRendered();

        List<String> unreachable = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            for (Method member : type.getDeclaredMethods()) {
                if (!carriesAuthoredText(member)) {
                    continue;
                }
                if (!rendered.contains(GuardrailModels.FIXTURE_PREFIX + member.getName())) {
                    unreachable.add(type.getSimpleName() + "." + member.getName() + "()");
                }
            }
        }

        assertEquals(List.of(), unreachable,
            "these members are accepted at the annotation and reach no generated file, so what the "
                + "author wrote reaches nobody while the build stays green and the file still looks "
                + "right. Render the member, or remove it from the annotation");
    }

    /**
     * Guards the sweep: if the fixture stopped answering members with a distinctive value, every
     * {@code contains} above would be asking about a string that is in every file, or in none, and
     * the sweep would pass without looking at anything.
     */
    @Test
    void theFixtureAnswersTextMembersDistinctively() {
        String rendered = everythingRendered();
        assertTrue(rendered.contains(GuardrailModels.FIXTURE_PREFIX + "reason"),
            "the fixture must render reason() as its own marked value for the sweep to mean anything");
        assertTrue(!rendered.contains(GuardrailModels.FIXTURE_PREFIX + "notAMemberOfAnything"),
            "and must not answer an unknown member, or every member would look reachable");
    }

    /** The union of every aggregate platform file and every per-element rule file. */
    private static String everythingRendered() {
        StringBuilder all = new StringBuilder();
        for (Platform platform : Platform.values()) {
            if (platform.name().endsWith("_GRANULAR")) {
                continue;
            }
            String out = PlatformRendererRegistry.getRenderer(platform)
                .render(GuardrailModels.everyAnnotation(), platform, CONTEXT);
            if (out != null) {
                all.append(out).append('\n');
            }
        }
        Map<TaggedElement, GranularBody> granular =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotation());
        for (GranularBody body : granular.values()) {
            all.append(body).append('\n');
        }
        return all.toString();
    }

    /** True for a member whose value is prose the author supplied: a String or an array of them. */
    private static boolean carriesAuthoredText(Method member) {
        Class<?> returned = member.getReturnType();
        return returned == String.class
            || (returned.isArray() && returned.getComponentType() == String.class);
    }
}
