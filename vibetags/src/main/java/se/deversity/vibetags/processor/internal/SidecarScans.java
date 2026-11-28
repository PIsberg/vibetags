package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.ModuleSidecar.FormatVersion;
import se.deversity.vibetags.processor.internal.ModuleSidecar.LineKind;

import java.io.Reader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two streaming reads of a sidecar: whether {@link ModuleSidecar#load} would refuse it as
 * unreadable or from the future ({@link #unreadableNames}), and what {@link ModuleSidecar#onRecord}
 * needs of it ({@link #summary}), each reached without materialising the file.
 *
 * <p>A sidecar is not a directory entry but its module's whole collected model, so reading every
 * one in full to answer a yes-or-no question cost a share of what they weigh on every build (#833).
 * These answer by streaming, one buffer per file. They must reach exactly the verdicts
 * {@code load} reaches, which is the whole point and is what
 * {@code ModuleSidecarUnreadableScanAgreementTest}, {@code ModuleSidecarOnRecordAgreementTest} and
 * {@code ModuleSidecarScanEdgeAgreementTest} hold them to. The format itself (keys, the version
 * header, the trailer) stays defined in {@link ModuleSidecar}.
 */
final class SidecarScans {

    private SidecarScans() {}

    static List<String> unreadableNames(Path root) {
        List<String> unreadable = new ArrayList<>();
        for (Path p : ModuleSidecar.listPaths(root)) {
            if (scanUnreadable(p)) {
                unreadable.add(GuardrailFileWriter.fileName(p));
            }
        }
        Collections.sort(unreadable);
        return unreadable;
    }

    static @Nullable ModuleSidecar summary(Path path, boolean readUnrouted) {
        SummaryScan scan = new SummaryScan(readUnrouted);
        char[] buffer = new char[ModuleSidecar.SCAN_BUFFER_CHARS];
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                for (int i = 0; i < read; i++) {
                    scan.accept(buffer[i]);
                }
            }
        } catch (IOException unreadable) {
            // load reads every line before it looks at any, so a file it cannot read to the end is
            // UNREADABLE whatever its first lines said, a future-version header included.
            return ModuleSidecar.UNREADABLE;
        }
        return scan.finish();
    }

    /**
     * Whether {@link ModuleSidecar#load} would answer {@link ModuleSidecar#UNREADABLE} or
     * {@link ModuleSidecar#FUTURE_VERSION} for this file, decided without materialising any of it.
     *
     * <p>Only three things in a sidecar can produce either verdict, and none of them is a body: the
     * {@code # version} header, whether the file opens and decodes as UTF-8 at all, and whether
     * {@link ModuleSidecar#TRAILER} is its last non-blank line. Every other outcome {@code load}
     * can reach - healthy, stale, corrupt, headerless - is a {@code null} or a sidecar, and the
     * caller names neither, so the base64 below the headers cannot change this answer and is read
     * past rather than decoded.
     *
     * <p>The whole file is still decoded, not just its two ends: {@code load} reaches
     * {@code UNREADABLE} through an {@code IOException}, and invalid UTF-8 in the middle of a file
     * is one. Seeking straight to the trailer would be cheaper and would stop naming that file,
     * which is the silent-disappearance shape this warning exists to break (issue #592).
     *
     * <p>{@code ModuleSidecarUnreadableScanAgreementTest} holds this to the classification
     * {@code load} reaches, case by case, which is what makes the reasoning above falsifiable
     * rather than merely stated.
     */
    private static boolean scanUnreadable(Path path) {
        TrailerScan scan = new TrailerScan();
        char[] buffer = new char[ModuleSidecar.SCAN_BUFFER_CHARS];
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                for (int i = 0; i < read; i++) {
                    Boolean verdict = scan.accept(buffer[i]);
                    if (verdict != null) {
                        return verdict;
                    }
                }
            }
        } catch (IOException unreadable) {
            // Could not read it, or it is not UTF-8. See UNREADABLE.
            return true;
        }
        scan.endLine();
        // Only a version that promises a trailer may be judged by one; see load's own comment.
        return scan.version == FormatVersion.PROMISES_TRAILER && !scan.lastNonBlankIsTrailer;
    }

    /**
     * The running state {@link #scanUnreadable} keeps while it streams a sidecar past: the format
     * version, and whether the last non-blank line so far is {@link ModuleSidecar#TRAILER}.
     *
     * <p>Fixed size on purpose. It captures a line only while that line's first non-blank
     * character is a hash, and only up to {@link #CAPTURE_CHARS}, so the base64 bodies - which are
     * the reason this class exists - flow through it without being held. What it captures is what
     * {@code isWhole} would have compared against, kept honest about leading whitespace: a line's
     * stripped form is what decides, so an indented trailer is still a trailer and a longer line
     * is not one, both exactly as {@code String.strip} has it.
     */
    private static final class TrailerScan {

        /**
         * Long enough for the trailer, which is the only thing a comment line that is not a
         * version header is inspected for. A version header is kept whole, however long.
         */
        private static final int CAPTURE_CHARS = 128;

        private @Nullable FormatVersion version;
        private boolean lastNonBlankIsTrailer;

        private final StringBuilder captured = new StringBuilder(CAPTURE_CHARS);
        private int column;
        private int firstNonBlank = -1;
        private int strippedLength;
        private boolean capturing;
        /**
         * Whether this line opens with {@link ModuleSidecar#VERSION_HEADER}. Such a line is
         * captured to its end: {@code load} parses all of it, so a number cut at
         * {@link #CAPTURE_CHARS} would be read as a different one, or as none (#1154).
         */
        private boolean versionHeader;

        /**
         * Feeds one character in.
         *
         * @return {@code null} to carry on, or the final verdict when the version header has
         *     already settled it, at the same line where {@code load} returns on it
         */
        @Nullable Boolean accept(char c) {
            if (c == '\n' || c == '\r') {
                // readAllLines breaks on LF, CR and CRLF alike, and the empty line a CRLF leaves
                // between the two is blank, so endLine finds nothing to record for it.
                Boolean verdict = endLineWithVerdict();
                resetLine();
                return verdict;
            }
            if (!Character.isWhitespace(c)) {
                if (firstNonBlank < 0) {
                    firstNonBlank = column;
                    capturing = c == '#';
                }
                strippedLength = column - firstNonBlank + 1;
            }
            if (capturing && (versionHeader || captured.length() < CAPTURE_CHARS)) {
                captured.append(c);
                if (captured.length() == ModuleSidecar.VERSION_HEADER.length()) {
                    // firstNonBlank == 0 is what the startsWith in load requires: a header
                    // indented by a space is not one.
                    versionHeader = firstNonBlank == 0 && ModuleSidecar.VERSION_HEADER.contentEquals(captured);
                }
            }
            column++;
            return null;
        }

        /** Closes the last line when the file did not end with a terminator. */
        void endLine() {
            endLineWithVerdict();
            resetLine();
        }

        private @Nullable Boolean endLineWithVerdict() {
            if (firstNonBlank < 0) {
                return null; // blank line: isWhole skips it, so it cannot be the trailer
            }
            lastNonBlankIsTrailer = strippedLength == ModuleSidecar.TRAILER.length()
                && captured.length() >= ModuleSidecar.TRAILER.length()
                && ModuleSidecar.TRAILER.contentEquals(captured.subSequence(0, ModuleSidecar.TRAILER.length()));
            return versionVerdict();
        }

        /**
         * Applies the {@code # version} header the way {@code load} does, including where it
         * returns early: a newer format is {@link ModuleSidecar#FUTURE_VERSION} and is named, while
         * an older one and an unparseable one are {@code null} and are not.
         */
        private @Nullable Boolean versionVerdict() {
            if (!versionHeader) {
                return null;
            }
            FormatVersion header = FormatVersion.of(captured.substring(ModuleSidecar.VERSION_HEADER.length()));
            if (header == FormatVersion.FUTURE) {
                return Boolean.TRUE;
            }
            if (header == FormatVersion.STALE) {
                return Boolean.FALSE;
            }
            version = header;
            return null;
        }

        private void resetLine() {
            captured.setLength(0);
            column = 0;
            firstNonBlank = -1;
            strippedLength = 0;
            capturing = false;
            versionHeader = false;
        }
    }

    /** Stands in for a non-blank unrouted body in a {@link ModuleSidecar#loadSummary} result. */
    private static final String SUMMARIZED_BODY = "(summarized)";

    /**
     * The state {@link ModuleSidecar#loadSummary} keeps while it streams a sidecar past, applying
     * {@link ModuleSidecar#load}'s rules line by line in file order without holding a body.
     *
     * <p>A line is classified by its key as it arrives, and its value is then kept (the three
     * headers and {@code ~elements}, all small), decoded for blankness only ({@code ~tfull~}),
     * checked for decodability only (every other value {@code load} decodes), or ignored (a
     * reserved key {@code load} does not recognise, which it never decodes either). The first
     * refusal in file order decides the verdict, as the first {@code return} or exception does in
     * {@code load}; the rest of the file is still read, so bytes that are not UTF-8 anywhere in it
     * make it {@link ModuleSidecar#UNREADABLE} as {@code readAllLines} does.
     */
    private static final class SummaryScan {

        /** What a line's value is for, decided by its key. */
        private enum Kind { IGNORED, HEADER, ELEMENTS, UNROUTED, CHECKED }

        private final boolean readUnrouted;

        private @Nullable String moduleId;
        private String modulePath = "";
        private @Nullable String regionId;
        private @Nullable FormatVersion version;
        private final Set<String> elementIds = new LinkedHashSet<>();
        private final Map<String, String> unrouted = new LinkedHashMap<>();
        private boolean lastNonBlankIsTrailer;

        /** Set by the first refusal; {@link #verdict} is then {@code null} or FUTURE_VERSION. */
        private boolean refused;

        /**
         * Set by the first value that will not decode. Unlike a refusal it is judged after the
         * trailer, as {@code load} judges it: a file with no trailer was cut off, possibly inside
         * that very value, and is UNREADABLE rather than corrupt. Only the trailer is tracked from
         * here on, since {@code load} stops reading content at the same value.
         */
        private boolean undecodable;
        private @Nullable ModuleSidecar verdict;

        private boolean lineStarted;
        private boolean comment;
        private boolean inValue;
        private Kind kind = Kind.IGNORED;
        private final StringBuilder key = new StringBuilder();
        private String keyName = "";
        private final StringBuilder captured = new StringBuilder();
        private final Base64Stream stream = new Base64Stream();

        private int column;
        private int firstNonBlank = -1;
        private int strippedLength;
        private final StringBuilder trailerProbe = new StringBuilder(ModuleSidecar.TRAILER.length());

        SummaryScan(boolean readUnrouted) {
            this.readUnrouted = readUnrouted;
        }

        void accept(char c) {
            if (c == '\n' || c == '\r') {
                // readAllLines ends a line at LF, CR and CRLF alike; the empty line a CRLF leaves
                // between its two halves holds nothing, so no rule below can see it.
                endLine();
                return;
            }
            if (refused) {
                return;
            }
            probeTrailer(c);
            if (undecodable) {
                return;
            }
            if (!lineStarted) {
                lineStarted = true;
                comment = c == '#';
            }
            if (comment) {
                // Only a version header is ever read back, so a comment stops being kept once it
                // can no longer be one.
                if (captured.length() < ModuleSidecar.VERSION_HEADER.length()
                        || captured.indexOf(ModuleSidecar.VERSION_HEADER) == 0) {
                    captured.append(c);
                }
                return;
            }
            if (!inValue) {
                if (c == '=') {
                    inValue = true;
                    keyName = key.toString();
                    kind = classify(keyName);
                    stream.start(kind == Kind.UNROUTED);
                } else {
                    key.append(c);
                }
                return;
            }
            switch (kind) {
                case HEADER, ELEMENTS -> captured.append(c);
                case UNROUTED, CHECKED -> stream.accept(c);
                case IGNORED -> { }
            }
        }

        /**
         * What this scan does with a value of each kind {@code load} tells apart. No default arm,
         * so a kind added to {@link LineKind} does not compile until this says how it is read.
         */
        private Kind classify(String k) {
            return switch (LineKind.of(k)) {
                case MODULE_ID, MODULE_PATH, REGION_ID -> Kind.HEADER;
                case ELEMENT_IDS -> Kind.ELEMENTS;
                case UNROUTED_BODY -> readUnrouted ? Kind.UNROUTED : Kind.CHECKED;
                case RESERVED -> Kind.IGNORED;
                case GRANULAR_STEMS, SOURCE_ROOTS, MODULE_GRANULAR_NAMING, GRANULAR_NAMING,
                     MODULE_GRANULAR_UNIT, GRANULAR_UNIT, MODULE_BODY, INDEX_DIGEST, SERVICE_BODY -> Kind.CHECKED;
            };
        }

        /**
         * Tracks whether this line, stripped, is {@link ModuleSidecar#TRAILER}, the way
         * {@code isWhole} asks.
         */
        private void probeTrailer(char c) {
            if (!Character.isWhitespace(c)) {
                if (firstNonBlank < 0) {
                    firstNonBlank = column;
                }
                strippedLength = column - firstNonBlank + 1;
            }
            if (firstNonBlank >= 0 && trailerProbe.length() < ModuleSidecar.TRAILER.length()) {
                trailerProbe.append(c);
            }
            column++;
        }

        private void endLine() {
            if (!refused) {
                if (firstNonBlank >= 0) {
                    lastNonBlankIsTrailer = strippedLength == ModuleSidecar.TRAILER.length()
                        && ModuleSidecar.TRAILER.contentEquals(trailerProbe);
                }
                if (comment) {
                    readVersionHeader();
                } else if (inValue) {
                    finishValue();
                }
            }
            lineStarted = false;
            comment = false;
            inValue = false;
            kind = Kind.IGNORED;
            key.setLength(0);
            captured.setLength(0);
            column = 0;
            firstNonBlank = -1;
            strippedLength = 0;
            trailerProbe.setLength(0);
        }

        private void readVersionHeader() {
            if (captured.indexOf(ModuleSidecar.VERSION_HEADER) != 0) {
                return;
            }
            FormatVersion header = FormatVersion.of(captured.substring(ModuleSidecar.VERSION_HEADER.length()));
            switch (header) {
                case FUTURE -> refuse(ModuleSidecar.FUTURE_VERSION);
                case STALE -> refuse(null);
                case PRE_TRAILER, PROMISES_TRAILER -> { version = header; }
            }
        }

        private void finishValue() {
            switch (kind) {
                case HEADER -> finishHeader(captured.toString());
                case ELEMENTS -> {
                    try {
                        ModuleSidecar.addNonBlankLines(ModuleSidecar.decode(captured.toString()), elementIds);
                    } catch (IllegalArgumentException malformed) {
                        undecodable = true;
                    }
                }
                case UNROUTED -> {
                    if (stream.finish()) {
                        unrouted.put(keyName.substring(ModuleSidecar.KEY_UNROUTED_BODY_PREFIX.length()),
                            stream.nonBlank() ? SUMMARIZED_BODY : "");
                    } else {
                        undecodable = true;
                    }
                }
                case CHECKED -> {
                    if (!stream.finish()) undecodable = true;
                }
                case IGNORED -> { }
            }
        }

        private void finishHeader(String value) {
            if (ModuleSidecar.KEY_MODULE_ID.equals(keyName)) {
                moduleId = value;
            } else if (ModuleSidecar.KEY_MODULE_PATH.equals(keyName)) {
                modulePath = value;
            } else {
                regionId = value;
            }
        }

        private void refuse(@Nullable ModuleSidecar sentinel) {
            refused = true;
            verdict = sentinel;
        }

        @Nullable ModuleSidecar finish() {
            endLine();
            if (refused) {
                return verdict;
            }
            if (version == FormatVersion.PROMISES_TRAILER && !lastNonBlankIsTrailer) return ModuleSidecar.UNREADABLE;
            if (undecodable || moduleId == null || version == null) return null;
            return ModuleSidecar.summarized(moduleId, modulePath,
                regionId != null && !regionId.isBlank() ? regionId : moduleId, elementIds, unrouted);
        }
    }

    /**
     * A base64 value checked as it streams past: whether {@code Base64.getDecoder()} would decode
     * it at all, and optionally whether the text it decodes to is blank, without building either
     * the bytes or the string.
     *
     * <p>Decodability follows the basic decoder's rules exactly: the 64-character alphabet and
     * nothing else, padding only as {@code xx==} or {@code xxx=} and only at the end, and no
     * dangling single character in the last unit. Unpadded input is accepted, as the decoder
     * accepts it.
     *
     * <p>Blankness follows {@code String.isBlank} over the UTF-8 decoding. The only whitespace
     * outside ASCII is a handful of three-byte characters (U+1680, U+2000 to U+200A less U+2007,
     * U+2028, U+2029, U+205F, U+3000), all with a lead byte of E1 to E3, so any other non-ASCII
     * byte, and any sequence that does not complete, decodes to something that is not whitespace.
     */
    private static final class Base64Stream {

        private static final int FIRST_SHIFT = 18;
        private static final int SEXTET = 6;
        private static final int ASCII = 128;
        private static final int[] SEXTETS = new int[ASCII];

        static {
            java.util.Arrays.fill(SEXTETS, -1);
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
            for (int i = 0; i < alphabet.length(); i++) {
                SEXTETS[alphabet.charAt(i)] = i;
            }
        }

        private boolean judgeBlank;
        private boolean valid;
        private int shift;
        private int bits;
        /** 0 before any padding, 1 after {@code xx=} (one more {@code =} is owed), 2 after the end. */
        private int padding;
        private boolean nonBlank;
        private int codePoint;
        private int continuationsOwed;

        void start(boolean judge) {
            judgeBlank = judge;
            valid = true;
            shift = FIRST_SHIFT;
            bits = 0;
            padding = 0;
            nonBlank = false;
            codePoint = 0;
            continuationsOwed = 0;
        }

        void accept(char c) {
            if (!valid) {
                return;
            }
            if (padding == 2) {
                valid = false; // anything after the padding
                return;
            }
            if (padding == 1) {
                if (c == '=') {
                    padding = 2;
                    emit(bits >> 16);
                } else {
                    valid = false;
                }
                return;
            }
            if (c == '=') {
                if (shift == SEXTET) {
                    padding = 1;
                } else if (shift == 0) {
                    padding = 2;
                    emit(bits >> 16);
                    emit(bits >> 8);
                } else {
                    valid = false; // "=" opening a unit, or "x=": the decoder refuses both
                }
                return;
            }
            int sextet = c < ASCII ? SEXTETS[c] : -1;
            if (sextet < 0) {
                valid = false;
                return;
            }
            bits |= sextet << shift;
            shift -= SEXTET;
            if (shift < 0) {
                emit(bits >> 16);
                emit(bits >> 8);
                emit(bits);
                shift = FIRST_SHIFT;
                bits = 0;
            }
        }

        /** Closes the value; {@code false} when the decoder would have refused it. */
        boolean finish() {
            if (!valid || padding == 1) {
                return false;
            }
            if (padding == 0) {
                if (shift == FIRST_SHIFT - SEXTET) {
                    return false; // a dangling single character
                }
                if (shift == SEXTET) {
                    emit(bits >> 16);
                } else if (shift == 0) {
                    emit(bits >> 16);
                    emit(bits >> 8);
                }
            }
            if (continuationsOwed > 0) {
                nonBlank = true; // a sequence cut short decodes to U+FFFD
            }
            return true;
        }

        boolean nonBlank() {
            return nonBlank;
        }

        private void emit(int value) {
            if (!judgeBlank || nonBlank) {
                return;
            }
            int b = value & 0xFF;
            if (continuationsOwed > 0) {
                if ((b & 0xC0) != 0x80) {
                    nonBlank = true;
                    return;
                }
                codePoint = (codePoint << SEXTET) | (b & 0x3F);
                if (--continuationsOwed == 0 && !Character.isWhitespace(codePoint)) {
                    nonBlank = true;
                }
                return;
            }
            if (b < ASCII) {
                nonBlank = !Character.isWhitespace(b);
            } else if (b >= 0xE1 && b <= 0xE3) {
                codePoint = b & 0x0F;
                continuationsOwed = 2;
            } else {
                nonBlank = true;
            }
        }
    }

}
