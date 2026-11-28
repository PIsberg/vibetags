package se.deversity.vibetags.processor.internal.validation;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.MarkerLines;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A top-level key written by hand outside the VibeTags block of a generated YAML file, when the
 * block writes the same key (issue #635).
 *
 * <p>The marker merge keeps hand-authored text outside the block, as invariant 2 requires, so both
 * halves survive and the document ends up declaring the key twice. No part of that is visible to the
 * writer: each half is individually correct. A loader that tolerates duplicates, PyYAML among them,
 * keeps the last occurrence and drops the other silently; a strict one rejects the document. Either
 * way somebody's configuration stops applying and nothing says why.
 *
 * <p>This is not a {@link ValidationRule}: those inspect annotated elements during a live round,
 * and this inspects a file on disk after the last round. It lives in this package all the same,
 * because it is a check, and the entry point that runs it
 * ({@code HandAuthoredYamlKeyWarner}) holds no logic of its own.
 *
 * <p>Which keys VibeTags owns is read off the renderer rather than listed here, by rendering the
 * platform for an empty model: the scaffold's top-level keys do not depend on the annotations, and a
 * second list of them would be a twin of every YAML renderer, free to drift.
 */
public final class DuplicateYamlKeyRule {

    /** A plain or quoted mapping key at column 0, followed by a colon and a space or end of line. */
    private static final Pattern TOP_LEVEL_KEY =
        Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*|\"[^\"]+\"|'[^']+')[ \\t]*:(?:[ \\t]|$)");

    /** A YAML document separator. Keys either side of one belong to different mappings. */
    private static final Pattern DOCUMENT_START = Pattern.compile("^---(?:[ \\t].*)?$");

    /** U+FEFF, which some editors save at the start of a UTF-8 file and the writer writes back. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * Files whose reading tool is known, and known to keep the last occurrence. Only named where it
     * has been checked: aider loads its config with PyYAML. For the others the message says what a
     * last-wins loader would do rather than claiming which loader the tool uses.
     */
    private static final Map<String, String> LAST_WINS_READERS = Map.of(".aider.conf.yml", "aider");

    private DuplicateYamlKeyRule() {
    }

    /**
     * One key declared both by the VibeTags block and by hand.
     *
     * @param key           the key, unquoted
     * @param handLine      1-based line of the hand-authored occurrence
     * @param generatedLine 1-based line of the block's occurrence, or {@code 0} when the block on
     *                      disk does not carry the key yet and this build is about to write it
     * @param readLine      the line a last-wins loader reads: {@code handLine}, {@code generatedLine},
     *                      or {@code 0} for the not-yet-written generated key
     */
    public record Finding(String key, int handLine, int generatedLine, int readLine) {

        /** Whether the hand-authored occurrence is the one that silently wins. */
        public boolean handAuthoredWins() {
            return readLine == handLine;
        }
    }

    /**
     * The top-level keys the renderer behind {@code serviceKey} writes, or an empty set when the
     * service has no renderer.
     */
    public static Set<String> ownedKeys(String serviceKey) {
        Platform platform = Platform.fromServiceKey(serviceKey);
        if (platform == null) {
            return Set.of();
        }
        String rendered = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModel.EMPTY, platform, new RenderingContext("", "", Set.of(serviceKey)));
        Set<String> keys = new LinkedHashSet<>();
        if (rendered == null) {
            return keys;
        }
        for (String line : rendered.split("\n", -1)) {
            String key = topLevelKey(line);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Every owned key that also appears, at the top level of the same document, outside the block.
     *
     * @param content     the file as it is on disk
     * @param ownedKeys   the keys the block writes, from {@link #ownedKeys}
     * @param markerStart the line that opens the VibeTags block
     * @param markerEnd   the line that closes it
     */
    public static List<Finding> find(String content, Set<String> ownedKeys, String markerStart, String markerEnd) {
        String text = content.replace("\r\n", "\n");
        // A byte order mark is encoding, not content: the writer looks for its markers behind one
        // and writes it back, and left in place it hid the block and a first-line key (#1194).
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        String[] lines = text.split("\n", -1);
        return Occurrences.scan(lines, ownedKeys, Block.locate(text, lines.length, markerStart, markerEnd)).findings();
    }

    /**
     * Where the VibeTags block lies, as 0-based line indexes of its two marker lines. {@code start}
     * is {@code -1} when there is no block; an unclosed block runs to the end, as the writer treats
     * it, so {@code end} is then the line count.
     */
    private record Block(int start, int end) {

        /**
         * The block the writer would replace in {@code text}, found with the writer's own search
         * rather than a second marker rule: a marker with whitespace before it still owns its line,
         * and one inside a code fence is not the block (#1194).
         */
        static Block locate(String text, int lineCount, String markerStart, String markerEnd) {
            int start = MarkerLines.indexOfBlockStart(text, markerStart);
            if (start < 0) {
                return new Block(-1, lineCount);
            }
            int end = MarkerLines.indexOfMarkerLine(text, markerEnd, start + markerStart.length());
            return new Block(lineOf(text, start), end < 0 ? lineCount : lineOf(text, end));
        }

        /** The 0-based line {@code index} falls on. */
        private static int lineOf(String text, int index) {
            int line = 0;
            for (int i = text.indexOf('\n'); i >= 0 && i < index; i = text.indexOf('\n', i + 1)) {
                line++;
            }
            return line;
        }

        boolean exists() {
            return start >= 0;
        }

        /** Whether line {@code index} lies between the markers. */
        boolean contains(int index) {
            return exists() && index > start && index < end;
        }

        /** Whether the block closes above line {@code index}. */
        boolean closesAbove(int index) {
            return exists() && index > end;
        }
    }

    /** A top-level key's 1-based line, and the 0-based YAML document it is declared in. */
    private record Occurrence(int line, int document) {
    }

    /** Every top-level occurrence of an owned key, split into the block's and the hand-written ones. */
    private static final class Occurrences {

        private final Block block;
        private final Map<String, Occurrence> generated = new LinkedHashMap<>();
        private final Map<String, List<Occurrence>> handWritten = new LinkedHashMap<>();
        private int blockDocument;
        private int lastDocument;

        private Occurrences(Block block) {
            this.block = block;
        }

        static Occurrences scan(String[] lines, Set<String> ownedKeys, Block block) {
            Occurrences occurrences = new Occurrences(block);
            int document = 0;
            for (int i = 0; i < lines.length; i++) {
                if (DOCUMENT_START.matcher(lines[i]).matches()) {
                    document++;
                } else {
                    occurrences.add(i, document, topLevelKey(lines[i]), ownedKeys);
                }
            }
            // An appended block goes below everything, into the document the last separator
            // opens, whether or not a line follows it (#1194).
            occurrences.lastDocument = document;
            return occurrences;
        }

        private void add(int index, int document, @Nullable String key, Set<String> ownedKeys) {
            if (index == block.start()) {
                blockDocument = document;
            }
            if (key == null || !ownedKeys.contains(key)) {
                return;
            }
            Occurrence occurrence = new Occurrence(index + 1, document);
            if (block.contains(index)) {
                generated.putIfAbsent(key, occurrence);
            } else {
                handWritten.computeIfAbsent(key, k -> new ArrayList<>()).add(occurrence);
            }
        }

        List<Finding> findings() {
            List<Finding> findings = new ArrayList<>();
            for (Map.Entry<String, List<Occurrence>> hand : handWritten.entrySet()) {
                Finding finding = findingFor(hand.getKey(), hand.getValue());
                if (finding != null) {
                    findings.add(finding);
                }
            }
            return findings;
        }

        /**
         * The clash between {@code key}'s hand-written occurrences and the block's, or {@code null}
         * when they are only in another YAML document, which is a separate mapping.
         */
        private @Nullable Finding findingFor(String key, List<Occurrence> hand) {
            Occurrence ours = generated.get(key);
            if (ours == null) {
                // Not in the block on disk: this build writes it, and where it lands decides who
                // wins. No block at all means it is appended below everything, into the last document.
                int handLine = lastLineIn(hand, block.exists() ? blockDocument : lastDocument);
                boolean handBelow = block.closesAbove(handLine - 1);
                return handLine == 0 ? null : new Finding(key, handLine, 0, handBelow ? handLine : 0);
            }
            int handLine = lastLineIn(hand, ours.document());
            return handLine == 0 ? null
                : new Finding(key, handLine, ours.line(), Math.max(handLine, ours.line()));
        }

        /**
         * The last of {@code occurrences} in {@code document}, or {@code 0}. The last, because a key
         * written twice by hand is read at its lower occurrence too, and that is the line the user
         * has to be pointed at.
         */
        private static int lastLineIn(List<Occurrence> occurrences, int document) {
            int line = 0;
            for (Occurrence occurrence : occurrences) {
                if (occurrence.document() == document) {
                    line = occurrence.line();
                }
            }
            return line;
        }
    }

    /**
     * The diagnostic for {@code finding}, without the {@code VibeTags: } prefix.
     *
     * @param fileName the file as shown to the user, a bare name or a {@code /}-separated path
     */
    public static String message(String fileName, Finding finding) {
        String where = finding.generatedLine() > 0
            ? "at line " + finding.generatedLine() + ", inside the VibeTags block"
            : "in the VibeTags block this build writes";
        String reader = LAST_WINS_READERS.get(fileName.substring(fileName.lastIndexOf('/') + 1));
        String read;
        if (reader != null) {
            read = reader + " reads " + (finding.handAuthoredWins()
                ? "line " + finding.handLine() + ", yours, and ignores the generated one"
                : finding.generatedLine() > 0
                    ? "line " + finding.generatedLine() + ", the generated one, and ignores yours"
                    : "the generated one and ignores yours")
                + ": PyYAML keeps the last occurrence of a key.";
        } else {
            read = "A loader that tolerates duplicate keys reads only "
                + (finding.handAuthoredWins() ? "yours, at line " + finding.handLine()
                    : "the generated one")
                + " and ignores the other; a strict one rejects the file.";
        }
        return fileName + " declares the top-level key '" + finding.key() + "' twice: at line "
            + finding.handLine() + ", which is outside the VibeTags markers, and " + where + ". " + read
            + " Remove the key you wrote. Moving its entries inside the block does not work: the block "
            + "is rewritten on every build and they would be lost.";
    }

    /** The key a column-0 mapping line declares, unquoted, or {@code null} for any other line. */
    static @Nullable String topLevelKey(String line) {
        Matcher m = TOP_LEVEL_KEY.matcher(line);
        if (!m.find()) {
            return null;
        }
        String key = m.group(1);
        char first = key.charAt(0);
        return first == '"' || first == '\'' ? key.substring(1, key.length() - 1) : key;
    }
}
