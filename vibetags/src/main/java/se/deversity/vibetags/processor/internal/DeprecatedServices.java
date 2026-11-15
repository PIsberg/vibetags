package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The outputs VibeTags still writes but will stop writing in the next major version, and the one
 * warning that tells an opted-in consumer so. 1.4.0 removed the seventeen rows this table carried
 * before (#720) and the table with them; it came back for {@code llms-full.txt} (#1071).
 *
 * <p>Each row names a file no tool reads: a retired product, or a path no vendor documents. They are
 * deprecated rather than removed because removing a service stops an opted-in consumer's file
 * regenerating, and a file that silently stops tracking the annotations is worse than one nobody
 * reads. The warning makes the removal visible a release before it lands.
 *
 * <p>This table is the only place the deprecation is stated in code. The evidence behind each row
 * lives in docs/PLATFORMS.md, which the warning points at.
 *
 * <p><strong>Once per compilation, not once per build.</strong> The warning is raised from the
 * root service resolution, which runs once per javac invocation, so a reactor prints it once per
 * module that compiles. Collapsing that to once per build would need state that outlives a
 * compilation, and a static set in a Gradle daemon outlives the build as well: the second build of
 * the same project would print nothing. One multi-line warning per compilation matches the AGENTS.md
 * note raised from the same method.
 */
public final class DeprecatedServices {

    /**
     * One deprecated output.
     *
     * @param file        the path as the user sees it, relative to the root, a directory with a
     *                    trailing {@code /}; pinned against
     *                    {@link ServiceRegistry#buildServiceFileMap} by DeprecatedServicesTest
     * @param why         the vendor fact, as one clause
     * @param advice      what to do instead, as one clause naming the replacement
     * @param replacement the replacement file(s) for the log event, comma-separated, no spaces
     */
    record Notice(String file, String why, String advice, String replacement) {}

    private static final Map<String, Notice> NOTICES = notices();

    private DeprecatedServices() {}

    private static Map<String, Notice> notices() {
        Map<String, Notice> m = new LinkedHashMap<>();
        // llms-full.txt: llmstxt.org never names it, and no vendor documents a tool that reads it
        // from a repository root. Devin Desktop (Windsurf), the tool PLATFORMS.md attributed it to,
        // names llms.txt only as its own docs-site index. Every other hit is a docs site publishing
        // /llms-full.txt, or a guide telling users to point a tool at the file by hand (#1071).
        m.put("llms_full", new Notice("llms-full.txt",
            "no tool documents reading a repository-root llms-full.txt, and the llms.txt standard"
                + " (llmstxt.org) never names the file",
            "keep llms.txt, which VibeTags also writes and is the file the standard names, or point"
                + " your tool at an instruction file it does read",
            "llms.txt"));
        return Collections.unmodifiableMap(m);
    }

    /** The deprecated service keys, in table order. */
    public static Set<String> keys() {
        return NOTICES.keySet();
    }

    /**
     * The replacement the warning names for {@code key}, as the log event records it
     * (comma-separated, {@code none} when there is none), or {@code null} when {@code key} is not
     * deprecated. Public for {@code vibetags init}, which marks the same keys.
     */
    public static @Nullable String replacement(String key) {
        Notice notice = NOTICES.get(key);
        return notice == null ? null : notice.replacement();
    }

    /** The user-facing path of each deprecated output, keyed by service key. */
    static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        NOTICES.forEach((key, notice) -> files.put(key, notice.file()));
        return files;
    }

    /**
     * Raises one WARNING naming every deprecated output in {@code active}, and logs one
     * {@code platform.deprecated} event per output. Does nothing when none is active.
     */
    public static void warnIfOptedIn(Messager messager, @Nullable Logger log, Set<String> active) {
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Notice> e : NOTICES.entrySet()) {
            if (!active.contains(e.getKey())) {
                continue;
            }
            Notice n = e.getValue();
            lines.append("\n  ").append(n.file()).append(": ").append(n.why())
                .append("; ").append(n.advice()).append('.');
            count++;
            if (log != null) {
                log.warn("platform.deprecated key={} file={} replacement={}",
                    e.getKey(), n.file(), n.replacement());
            }
        }
        if (count == 0) {
            return;
        }
        boolean one = count == 1;
        // build.yml's gradle-multimodule warning gate matches "opted-in output is deprecated" in
        // this first line, both to exclude it and to require it; rephrasing it breaks that gate.
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + (one ? "an opted-in output is" : count + " opted-in outputs are")
                + " deprecated. VibeTags still writes " + (one ? "it" : "them")
                + ", and will stop in the next major version:" + lines
                + "\n  To keep the guardrails, create the replacement, move any hand-written content"
                + " across, and delete the deprecated file or directory. The evidence is in docs/PLATFORMS.md.");
    }
}
