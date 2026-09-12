package se.deversity.vibetags.processor.internal.validation;

import org.jspecify.annotations.Nullable;
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
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        int blockStart = -1;
        int blockEnd = lines.length; // an unclosed block runs to the end, as the writer treats it
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            if (blockStart < 0 && line.equals(markerStart)) {
                blockStart = i;
            } else if (blockStart >= 0 && line.equals(markerEnd)) {
                blockEnd = i;
                break;
            }
        }

        int document = 0;
        int lastDocument = 0;
        Map<String, int[]> generated = new LinkedHashMap<>(); // key -> {line, document}
        Map<String, List<int[]>> hand = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            if (DOCUMENT_START.matcher(lines[i]).matches()) {
                document++;
                continue;
            }
            lastDocument = document;
            String key = topLevelKey(lines[i]);
            if (key == null || !ownedKeys.contains(key)) {
                continue;
            }
            boolean inBlock = blockStart >= 0 && i > blockStart && i < blockEnd;
            if (inBlock) {
                generated.putIfAbsent(key, new int[]{i + 1, document});
            } else {
                hand.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{i + 1, document});
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<int[]>> entry : hand.entrySet()) {
            String key = entry.getKey();
            int[] ours = generated.get(key);
            // Not in the block on disk: this build writes it. Where it lands decides who wins. No
            // block at all means it is appended below everything, into the last document.
            int ourDocument = ours != null ? ours[1] : (blockStart >= 0 ? documentOf(lines, blockStart) : lastDocument);
            int handLine = lastLineInDocument(entry.getValue(), ourDocument);
            if (handLine == 0) {
                continue; // only in another YAML document, which is a separate mapping
            }
            int generatedLine = ours != null ? ours[0] : 0;
            boolean handBelow = ours != null
                ? handLine > ours[0]
                : blockStart >= 0 && handLine - 1 > blockEnd; // below a block that lacks the key
            findings.add(new Finding(key, handLine, generatedLine, handBelow ? handLine : generatedLine));
        }
        return findings;
    }

    /**
     * The last of {@code occurrences} ({line, document} pairs) in {@code document}, or {@code 0}.
     * The last, because a key written twice by hand is read at its lower occurrence too, and that is
     * the line the user has to be pointed at.
     */
    private static int lastLineInDocument(List<int[]> occurrences, int document) {
        int line = 0;
        for (int[] occurrence : occurrences) {
            if (occurrence[1] == document) {
                line = occurrence[0];
            }
        }
        return line;
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

    private static int documentOf(String[] lines, int index) {
        int document = 0;
        for (int i = 0; i < index; i++) {
            if (DOCUMENT_START.matcher(lines[i]).matches()) {
                document++;
            }
        }
        return document;
    }
}
