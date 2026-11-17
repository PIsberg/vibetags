package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The four service-key lookups on {@link PlatformRendererRegistry} answer exactly what the
 * platform's own renderer declares, and answer "nothing" rather than throw for a key with no
 * renderer.
 *
 * <p>The multi-module merge only ever holds a service key, so these are the methods it asks.
 * Before this test only {@code mergeShapeFor} and {@code wholeFileMergeFor} had a direct case, and
 * only for unknown keys: nothing pinned that {@code sourceSetMergeFor} reaches the renderer's
 * {@code sourceSetMerge()} rather than another hook, or that {@code filePrologueFor} answers
 * {@code ""}, not {@code null}, for a key it cannot resolve. A mixed-up hook would merge
 * {@code CLAUDE.md} as concatenation, or a YAML file as Markdown, with nothing failing in a
 * single-module build.
 */
class PlatformRendererRegistryServiceKeyTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Platform.class)
    @DisplayName("each lookup answers what the platform's renderer declares")
    void lookupsMatchTheRenderer(Platform platform) {
        String key = platform.getServiceKey();
        PlatformRenderer renderer = PlatformRendererRegistry.getRenderer(platform);

        // YamlMergeShape is a record, so equality is by value; the renderer builds a new one per call.
        assertEquals(renderer.mergeShape(), PlatformRendererRegistry.mergeShapeFor(key),
            key + ": mergeShapeFor must return the renderer's mergeShape()");
        assertEquals(renderer.filePrologue(), PlatformRendererRegistry.filePrologueFor(key),
            key + ": filePrologueFor must return the renderer's filePrologue()");
        // The merges are lambdas or shared constants: compare presence and implementing class.
        assertEquals(classOf(renderer.sourceSetMerge()),
            classOf(PlatformRendererRegistry.sourceSetMergeFor(key)),
            key + ": sourceSetMergeFor must return the renderer's sourceSetMerge()");
        assertEquals(classOf(renderer.wholeFileMerge()),
            classOf(PlatformRendererRegistry.wholeFileMergeFor(key)),
            key + ": wholeFileMergeFor must return the renderer's wholeFileMerge()");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"root_index", "no-such-service", ""})
    @DisplayName("a key with no renderer answers nothing rather than throwing")
    void unknownKeyAnswersNothing(String key) {
        assertNull(PlatformRendererRegistry.mergeShapeFor(key));
        assertNull(PlatformRendererRegistry.sourceSetMergeFor(key));
        assertNull(PlatformRendererRegistry.wholeFileMergeFor(key));
        assertEquals("", PlatformRendererRegistry.filePrologueFor(key),
            "filePrologueFor is the one lookup that is not nullable: callers prefix-match on it");
    }

    private static String classOf(@Nullable Object merge) {
        return merge == null ? "none" : merge.getClass().getName().replaceAll("\\$\\$Lambda.*", "\\$\\$Lambda");
    }
}
