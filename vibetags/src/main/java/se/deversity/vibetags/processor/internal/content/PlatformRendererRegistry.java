package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.content.platforms.GranularRenderer;

/**
 * A central registry for retrieving the stateless PlatformRenderer for any given Platform.
 */
public final class PlatformRendererRegistry {

    private PlatformRendererRegistry() {}

    /**
     * Retrieves the renderer mapped to the specified platform enum.
     *
     * @param platform the platform type
     * @return the associated PlatformRenderer
     */
    public static PlatformRenderer getRenderer(Platform platform) {
        PlatformRenderer renderer = findRenderer(platform);
        if (renderer == null) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
        return renderer;
    }

    /**
     * The merge shape declared by the renderer behind {@code serviceKey}, or {@code null} when the
     * service has no renderer or its output is plain concatenable text.
     *
     * <p>Takes a service key rather than a {@link Platform} because the multi-module merge only ever
     * knows the key, and not every key has a renderer at all — {@code root_index} is an opt-in file
     * and nothing else, so this must answer "no shape" rather than throw.
     *
     * @param serviceKey the service key, e.g. {@code "sweep"}
     * @return the declared shape, or {@code null}
     */
    public static @Nullable YamlMergeShape mergeShapeFor(String serviceKey) {
        Platform platform = Platform.fromServiceKey(serviceKey);
        if (platform == null) {
            return null;
        }
        PlatformRenderer renderer = findRenderer(platform);
        return renderer == null ? null : renderer.mergeShape();
    }

    /** The prologue this service's renderer declares, or {@code ""} when it declares none. */
    public static String filePrologueFor(String serviceKey) {
        Platform platform = Platform.fromServiceKey(serviceKey);
        if (platform == null) {
            return "";
        }
        PlatformRenderer renderer = findRenderer(platform);
        return renderer == null ? "" : renderer.filePrologue();
    }

    /**
     * The whole-file merge declared by the renderer behind {@code serviceKey}, or {@code null} when
     * the service has no renderer, its file carries markers, or its output holds no per-element
     * content.
     *
     * @param serviceKey the service key, e.g. {@code "mentat"}
     * @return the declared merge, or {@code null}
     */
    public static @Nullable WholeFileMerge wholeFileMergeFor(String serviceKey) {
        Platform platform = Platform.fromServiceKey(serviceKey);
        if (platform == null) {
            return null;
        }
        PlatformRenderer renderer = findRenderer(platform);
        return renderer == null ? null : renderer.wholeFileMerge();
    }

    /**
     * The renderer declared for this platform in {@link PlatformDescriptors#ALL}, or {@code null}
     * when the platform has none.
     *
     * <p>This was a switch with one label per platform and a {@code default} arm that existed
     * because {@code GEMINI_GRANULAR} had been forgotten once (<a
     * href="https://github.com/PIsberg/vibetags/issues/762">issue #762</a>). A forgotten label did
     * not fail to compile and did not fail a test: it threw only on the path that filters the key
     * out first. The table has no default arm to fall through to.
     */
    private static @Nullable PlatformRenderer findRenderer(Platform platform) {
        PlatformDescriptor descriptor = PlatformDescriptors.byPlatform(platform);
        return descriptor == null ? null : descriptor.renderer();
    }

    public static GranularRenderer granularRenderer() {
        return PlatformDescriptors.granularRenderer();
    }
}
