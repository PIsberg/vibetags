package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.GuardrailModel;

/**
 * Defines the contract to render a single, specific platform configuration file.
 */
@FunctionalInterface
public interface PlatformRenderer {
    /**
     * Renders the platform configuration based on the collected annotations.
     *
     * @param model the accumulated annotations
     * @param platform the specific target platform/service
     * @param context the rendering context (project name, headers, etc.)
     * @return the rendered file contents, or null if this platform is not active or handled elsewhere
     */
    @Nullable String render(GuardrailModel model, Platform platform, RenderingContext context);

    /**
     * How this platform's output is combined across the modules of a reactor, or {@code null} when
     * stacking whole renderings is correct.
     *
     * <p>Only the YAML platforms override this. Markdown sections and ignore-file lists concatenate
     * without losing anything; a YAML document does not, because it has one of each top-level key
     * and repeating it either fails the parse or silently drops every module but the last.
     *
     * @return the merge shape, or {@code null} for plain concatenation
     * @see YamlMergeShape
     */
    default @Nullable YamlMergeShape mergeShape() {
        return null;
    }

    /**
     * How this platform's marker-free output is combined across the modules of a reactor, or
     * {@code null} when the file carries markers (and is merged by region) or holds no per-element
     * content at all.
     *
     * <p>Only the JSON and TOML platforms that render guardrails override this. A file with no
     * markers is a whole-file overwrite, so without a merge it carries one module's view of the
     * project — see {@link WholeFileMerge} for what that cost before this existed.
     *
     * @return the merge, or {@code null} for "no merge needed"
     */
    default @Nullable WholeFileMerge wholeFileMerge() {
        return null;
    }
}
