package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.SourceLocation;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import se.deversity.vibetags.processor.model.TaggedElement;

/**
 * Renders the machine-readable lock report ({@code .vibetags-locks}): one JSON object per
 * line for every {@code @AILocked} element, carrying the element path, source file, and
 * 1-based line range of the declaration.
 *
 * <p>The format is JSON Lines wrapped in the standard {@code # VIBETAGS} hash markers (the
 * file has no extension, so {@link se.deversity.vibetags.processor.internal.GuardrailFileWriter}
 * treats it as a hash-marker file). That choice is load-bearing: hash-marker files ride the
 * module-sidecar merge, so multi-module builds aggregate every module's locks with
 * {@code # VIBETAGS-MODULE} sub-markers instead of last-writer-wins. Consumers (e.g. the
 * locked-files GitHub Action) must parse line-by-line and skip lines starting with {@code #}.
 *
 * <p>Line positions come from the javac Tree API and are best-effort: under non-javac
 * compilers the {@code file}/{@code startLine}/{@code endLine} fields are omitted and tools
 * should fall back to file-level matching on the element path.
 */
public final class LocksReportRenderer implements PlatformRenderer {

    /**
     * Format version emitted as the first JSON record ({@code {"type":"format","version":N}}).
     * Bump when the per-entry schema changes incompatibly. Consumers that filter on
     * {@code type == "locked"} (like the bundled GitHub Action) skip the record automatically;
     * version-aware consumers can use it to reject reports they do not understand.
     */
    static final int FORMAT_VERSION = 1;

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.getGeneratedHeader())
          .append("# Machine-readable @AILocked report (JSON Lines; '#' lines are comments).\n")
          .append("{\"type\":\"format\",\"version\":").append(FORMAT_VERSION).append("}\n");

        for (TaggedElement e : model.locked()) {
            AILocked annotation = e.annotation(AILocked.class);
            String reason = annotation != null ? annotation.reason() : "";
            SourceLocation pos = model.lockedPosition(e);

            sb.append("{\"type\":\"locked\"")
              .append(",\"element\":\"").append(Escape.json(e.path())).append('"')
              .append(",\"kind\":\"").append(e.kind().name()).append('"');
            if (pos != null) {
                sb.append(",\"file\":\"").append(Escape.json(pos.file())).append('"')
                  .append(",\"startLine\":").append(pos.startLine())
                  .append(",\"endLine\":").append(pos.endLine());
            }
            sb.append(",\"reason\":\"").append(Escape.json(reason)).append('"')
              .append("}\n");
        }
        return sb.toString();
    }
}
