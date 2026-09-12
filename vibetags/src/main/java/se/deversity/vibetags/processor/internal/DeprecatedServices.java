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
 * warning that tells an opted-in consumer so (#641; the removal is tracked in #645).
 *
 * <p>Each row names a tool that has moved on: a retired product, or a file its vendor no longer
 * documents. They are deprecated rather than removed because removing a service stops an opted-in
 * consumer's file regenerating, and a file that silently stops tracking the annotations is worse
 * than one nobody reads. The warning makes the removal visible a release before it lands.
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
     * @param file        the path as the user sees it, relative to the root; pinned against
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
        m.put("gemini", new Notice("gemini_instructions.md",
            "no Google product documents reading this file",
            "use GEMINI.md for the Gemini CLI or .gemini/styleguide.md for Gemini Code Assist",
            "GEMINI.md,.gemini/styleguide.md"));
        m.put("cody", new Notice(".cody/config.json",
            "Sourcegraph retired Cody Free and Pro in July 2025",
            "its successor, Amp, reads AGENTS.md",
            "AGENTS.md"));
        m.put("cody_ignore", new Notice(".codyignore",
            "Sourcegraph retired Cody Free and Pro in July 2025",
            "its successor, Amp, reads AGENTS.md",
            "AGENTS.md"));
        m.put("supermaven_ignore", new Notice(".supermavenignore",
            "the standalone Supermaven product was discontinued in November 2025",
            "its technology ships in Cursor Tab, which reads .cursorignore",
            ".cursorignore"));
        m.put("cline", new Notice(".clinerules",
            "Cline's current docs describe a .clinerules/ directory, not this single file",
            "use the .clinerules/ directory; Cline also reads .cursorrules, .windsurfrules and AGENTS.md",
            ".clinerules/"));
        return Collections.unmodifiableMap(m);
    }

    /** The deprecated service keys, in table order. */
    public static Set<String> keys() {
        return NOTICES.keySet();
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
        // build.yml's gradle-multimodule warning gate matches "opted-in outputs are deprecated" in
        // this first line, both to exclude it and to require it; rephrasing it breaks that gate.
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + (one ? "an opted-in output is" : count + " opted-in outputs are")
                + " deprecated. VibeTags still writes " + (one ? "it" : "them")
                + ", and will stop in the next major version:" + lines
                + "\n  To keep the guardrails, create the replacement, move any hand-written content"
                + " across, and delete the deprecated file. The evidence is in docs/PLATFORMS.md.");
    }
}
