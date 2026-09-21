package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

/**
 * An always-loaded aggregate file and the glob-scoped rules directory that governs it.
 *
 * <p>The one declaration of each pairing (issue #763). It used to be spelled four times in two
 * layers: the granular writer's format table, {@code GranularIndexSection}'s three switches,
 * {@code ModuleSidecar}'s list of indexable aggregates with its own directory and file-name
 * switches, and a string concatenation in the processor that assumed the granular key is the
 * aggregate key plus {@code _granular}. They had already drifted: {@code GEMINI.md} collapsed to a
 * scoped-rules index in a single module and stayed embedded at a lean-indexed reactor root, and
 * for {@code gemini_md} the concatenation names a key that does not exist.
 *
 * <p>Paths are strings relative to the project or module root, with forward slashes, because this
 * layer may not import the service registry that owns the real paths.
 * {@code GranularPairingParityTest} holds the two to the same answer.
 *
 * <p>Declaration order is the order the lean root index iterates aggregates in. Append only.
 */
public enum GranularPairing {
    CLAUDE("claude", "claude_granular", "CLAUDE.md", ".claude/rules", ".md", true),
    CURSOR("cursor", "cursor_granular", ".cursorrules", ".cursor/rules", ".mdc", true),
    WINDSURF("windsurf", "windsurf_granular", ".windsurfrules", ".windsurf/rules", ".md", true),
    COPILOT("copilot", "copilot_granular", ".github/copilot-instructions.md",
        ".github/instructions", ".instructions.md", true),
    /**
     * Gemini CLI loads {@code GEMINI.md} files and never reads {@code .gemini/rules/} by itself
     * (#669), so text pointing there must send the agent to the file rather than promise a load.
     */
    GEMINI_MD("gemini_md", "gemini_granular", "GEMINI.md", ".gemini/rules", ".md", false);

    private final String aggregateKey;
    private final String granularKey;
    private final String aggregateFile;
    private final String scopedDir;
    private final String extension;
    private final boolean loadsScopedFilesOnOpen;

    GranularPairing(String aggregateKey, String granularKey, String aggregateFile, String scopedDir,
                    String extension, boolean loadsScopedFilesOnOpen) {
        this.aggregateKey = aggregateKey;
        this.granularKey = granularKey;
        this.aggregateFile = aggregateFile;
        this.scopedDir = scopedDir;
        this.extension = extension;
        this.loadsScopedFilesOnOpen = loadsScopedFilesOnOpen;
    }

    /** Service key of the always-loaded aggregate file, e.g. {@code claude}. */
    public String aggregateKey() {
        return aggregateKey;
    }

    /** Service key of the governing rules directory, e.g. {@code claude_granular}. */
    public String granularKey() {
        return granularKey;
    }

    /** The aggregate file, relative to its root, e.g. {@code CLAUDE.md}. */
    public String aggregateFile() {
        return aggregateFile;
    }

    /** The rules directory, relative to its root, with no trailing slash, e.g. {@code .claude/rules}. */
    public String scopedDir() {
        return scopedDir;
    }

    /** Filename suffix of a rule file, leading dot included, e.g. {@code .instructions.md}. */
    public String extension() {
        return extension;
    }

    /** Whether the reading tool loads a scoped rule file by itself once a matching source is open. */
    public boolean loadsScopedFilesOnOpen() {
        return loadsScopedFilesOnOpen;
    }

    /** The pairing whose aggregate has {@code aggregateKey}, or {@code null} when it has none. */
    public static @Nullable GranularPairing forAggregate(String aggregateKey) {
        for (GranularPairing p : values()) {
            if (p.aggregateKey.equals(aggregateKey)) {
                return p;
            }
        }
        return null;
    }

    /** The pairing whose rules directory has {@code granularKey}, or {@code null} when it has none. */
    public static @Nullable GranularPairing forGranular(String granularKey) {
        for (GranularPairing p : values()) {
            if (p.granularKey.equals(granularKey)) {
                return p;
            }
        }
        return null;
    }
}
