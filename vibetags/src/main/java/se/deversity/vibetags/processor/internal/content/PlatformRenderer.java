package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.processor.model.GuardrailModel;

/**
 * Defines the contract to render a single, specific platform configuration file.
 */
@AILoadBearing(
    invariant = "A renderer whose output is YAML declares mergeShape(); a renderer whose marker-free "
        + "output varies per module declares wholeFileMerge(). The defaults return null, which means "
        + "plain concatenation.",
    breaksIf = "Silent data loss across a reactor. Concatenated YAML repeats a top-level key, so the "
        + "parse either fails or keeps only the last module; a marker-free file is a whole-file "
        + "overwrite, so it ends up holding one module's view of the whole project. Neither shows up in "
        + "a single-module build, which is where a new renderer gets tested.")
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

    /**
     * How the bodies several source sets of one module rendered for this file are joined, or
     * {@code null} for blank-line concatenation.
     *
     * <p>Only the {@code CLAUDE.md} renderers override this. Concatenation loses nothing there,
     * but it repeats the whole {@code <project_guardrails>} scaffold once per source set, in a file
     * loaded on every session (issue #839). A YAML renderer does not need it: its
     * {@link #mergeShape()} already joins source sets, because for YAML concatenation is data loss.
     *
     * @return the merge, or {@code null} for plain concatenation
     */
    default @Nullable SourceSetMerge sourceSetMerge() {
        return null;
    }

    /**
     * Text every module's body of this file opens with, and which therefore belongs once at the
     * top of a reactor's file rather than inside each module's region.
     *
     * <p>Only {@code TESTING.md} declares one. Its regions each began with the same two-line
     * preamble saying what the file is scoped to — true of the file, not of any one module — so a
     * three-module reactor said it three times, in a feature whose whole purpose is to spend less
     * of the agent's context (issue #783).
     *
     * <p>Hoisted only when every region's body actually starts with it, so a module rendered by an
     * older processor, or one that renders something else entirely, keeps its own text rather than
     * having a prefix cut off it.
     *
     * @return the shared prologue, or {@code ""} when this platform has none
     */
    default String filePrologue() {
        return "";
    }
}
