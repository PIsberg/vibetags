package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.SourcePositionResolver;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import javax.lang.model.element.Element;

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

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.getGeneratedHeader())
          .append("# Machine-readable @AILocked report (JSON Lines; '#' lines are comments).\n");

        for (Element e : collector.locked()) {
            AILocked annotation = e.getAnnotation(AILocked.class);
            String reason = annotation != null ? annotation.reason() : "";
            SourcePositionResolver.Position pos = collector.lockedPosition(e);

            sb.append("{\"type\":\"locked\"")
              .append(",\"element\":\"").append(escapeJson(ElementNaming.elementPath(e))).append('"')
              .append(",\"kind\":\"").append(e.getKind().name()).append('"');
            if (pos != null) {
                sb.append(",\"file\":\"").append(escapeJson(pos.file())).append('"')
                  .append(",\"startLine\":").append(pos.startLine())
                  .append(",\"endLine\":").append(pos.endLine());
            }
            sb.append(",\"reason\":\"").append(escapeJson(reason)).append('"')
              .append("}\n");
        }
        return sb.toString();
    }

    /** Minimal JSON string escaping: backslash, quote, and control characters. */
    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
