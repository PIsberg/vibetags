package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two streaming reads of a sidecar ({@code unreadableSidecarNames} and {@code loadSummary}) agree
 * with {@link ModuleSidecar#load} where a streaming read can go wrong and a whole-file read cannot:
 * at the edges of its read buffer, and on line endings other than LF.
 *
 * <p>{@code ModuleSidecarUnreadableScanAgreementTest} and {@code ModuleSidecarOnRecordAgreementTest}
 * hold both scans to the full load across the format's content cases, on files that fit one buffer
 * and end lines with LF. These sweep files past the 8,192-character buffer so that the trailer, a
 * key and a value each straddle a read, and rewrite healthy and torn files with CRLF and lone CR.
 */
@DisplayName("Sidecar scans agree with the full load at buffer edges and on every line ending")
class ModuleSidecarScanEdgeAgreementTest {

    /** The scans read 8,192 characters at a time; sizes are swept across that edge. */
    private static final int BUFFER = 8192;

    @Test
    @DisplayName("a healthy sidecar larger than the buffer, the trailer at every offset across its edge, agrees")
    void theTrailerAcrossTheBufferEdge(@TempDir Path root) throws IOException {
        int named = 0;
        for (Path file : sweep(root, "big", true)) {
            named += agrees(root, file) ? 1 : 0;
            agreesPerFile(file);
            Files.delete(file);
        }
        assertEquals(0, named, "a healthy file is never named, wherever the buffer edge falls");
    }

    @Test
    @DisplayName("a torn sidecar larger than the buffer is named and UNREADABLE, wherever the edge falls")
    void aTornFileAcrossTheBufferEdge(@TempDir Path root) throws IOException {
        for (Path file : sweep(root, "torn", false)) {
            assertTrue(agrees(root, file), file.getFileName() + " has lost its trailer and must be named");
            assertSame(ModuleSidecar.UNREADABLE, agreesPerFile(file));
            Files.delete(file);
        }
    }

    @Test
    @DisplayName("an element list longer than the buffer is read whole by the summary")
    void anElementListAcrossTheBufferEdge(@TempDir Path root) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; ids.toString().length() < 3 * BUFFER; i++) {
            ids.add("com-example-generated-Element" + i);
        }
        Files.createDirectories(root.resolve("wide"));
        ModuleSidecar sidecar = new ModuleSidecar("wide", "wide");
        sidecar.putBody("claude", "body");
        sidecar.setElementIds(ids);
        sidecar.save(root);
        Path file = root.resolve(ModuleSidecar.SIDECAR_PREFIX + "wide");
        assertTrue(Files.size(file) > 2L * BUFFER, "precondition: the file spans several reads");

        ModuleSidecar full = agreesPerFile(file);
        assertFalse(agrees(root, file));
        assertEquals(ids, full.getElementIds());
    }

    @Test
    @DisplayName("CRLF and lone-CR line endings read the same as LF, healthy or torn")
    void lineEndings(@TempDir Path root) throws IOException {
        Path healthy = healthy(root, "crlf");
        String lf = Files.readString(healthy, StandardCharsets.UTF_8);
        String torn = lf.replace("\n# end", "");
        assertTrue(lf.contains("\n# end") && !torn.contains("# end"), "precondition: the trailer is a line of its own");

        for (String ending : List.of("\r\n", "\r")) {
            Files.writeString(healthy, lf.replace("\n", ending), StandardCharsets.UTF_8);
            assertFalse(agrees(root, healthy), "healthy with " + printable(ending));
            assertNotNull(agreesPerFile(healthy));

            Files.writeString(healthy, torn.replace("\n", ending), StandardCharsets.UTF_8);
            assertTrue(agrees(root, healthy), "torn with " + printable(ending));
            assertSame(ModuleSidecar.UNREADABLE, agreesPerFile(healthy));
        }
    }

    @Test
    @DisplayName("blank and whitespace lines after the trailer still leave it the last non-blank line")
    void blankLinesAfterTheTrailer(@TempDir Path root) throws IOException {
        Path file = healthy(root, "trailing");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text + "\n\n   \n\t\n", StandardCharsets.UTF_8);

        assertFalse(agrees(root, file));
        assertNotNull(agreesPerFile(file));
    }

    // ---------------------------------------------------------------- fixtures and the two relations

    /**
     * Sidecars of the same module whose body grows one character at a time, from where the trailer
     * ends just before the buffer edge to where it starts just after it, so that every offset of
     * the trailer, and of each key and value above it, falls on the edge once.
     */
    private static List<Path> sweep(Path root, String id, boolean keepTrailer) throws IOException {
        Files.createDirectories(root.resolve(id));
        List<Path> files = new java.util.ArrayList<>();
        int body = 0;
        while (fileFor(root, id, body, keepTrailer).length() < BUFFER - 60) {
            body += 8; // base64 makes each body character about 1.33 file characters
        }
        for (int extra = 0; extra < 64; extra++) {
            Path file = root.resolve(ModuleSidecar.SIDECAR_PREFIX + id + "-" + extra);
            Files.writeString(file, fileFor(root, id, body + extra, keepTrailer), StandardCharsets.UTF_8);
            files.add(file);
        }
        String first = Files.readString(files.get(0), StandardCharsets.UTF_8);
        String last = Files.readString(files.get(files.size() - 1), StandardCharsets.UTF_8);
        assertTrue(first.length() < BUFFER && last.length() > BUFFER,
            "precondition: the sweep crosses the buffer edge (" + first.length() + " to " + last.length() + ")");
        return files;
    }

    /** The text {@code save} writes for a module whose one body is {@code bodyChars} long. */
    private static String fileFor(Path root, String id, int bodyChars, boolean keepTrailer) throws IOException {
        ModuleSidecar sidecar = new ModuleSidecar(id, id);
        sidecar.putBody("claude", "x".repeat(Math.max(1, bodyChars)));
        sidecar.setElementIds(Set.of("com-example-" + id));
        Path scratch = Files.createDirectories(root.resolve("scratch"));
        sidecar.save(scratch);
        Path written = scratch.resolve(ModuleSidecar.SIDECAR_PREFIX + id);
        String text = Files.readString(written, StandardCharsets.UTF_8);
        Files.delete(written);
        return keepTrailer ? text : text.replace("\n# end", "");
    }

    private static Path healthy(Path root, String id) throws IOException {
        Files.createDirectories(root.resolve(id));
        ModuleSidecar sidecar = new ModuleSidecar(id, id);
        sidecar.putBody("claude", "a rendered guardrail body\nover two lines");
        sidecar.setElementIds(Set.of("com-example-" + id));
        sidecar.save(root);
        return root.resolve(ModuleSidecar.SIDECAR_PREFIX + id);
    }

    /** The scan names the file exactly when the full load answers UNREADABLE or FUTURE_VERSION. */
    private static boolean agrees(Path root, Path sidecar) {
        ModuleSidecar full = ModuleSidecar.load(sidecar);
        boolean fullSaysUnreadable = full == ModuleSidecar.UNREADABLE || full == ModuleSidecar.FUTURE_VERSION;
        boolean named = ModuleSidecar.unreadableSidecarNames(root).contains(GuardrailFileWriter.fileName(sidecar));
        assertEquals(fullSaysUnreadable, named, "the scan and the full load disagree about " + sidecar.getFileName());
        return named;
    }

    /** The summary reaches the full load's verdict, and for a sidecar its identity and element ids. */
    private static ModuleSidecar agreesPerFile(Path sidecar) {
        ModuleSidecar full = ModuleSidecar.load(sidecar);
        ModuleSidecar summary = ModuleSidecar.loadSummary(sidecar, true);
        String name = String.valueOf(sidecar.getFileName());
        if (full == null || full == ModuleSidecar.UNREADABLE || full == ModuleSidecar.FUTURE_VERSION) {
            assertSame(full, summary, name + ": the summary must reach the full load's verdict");
            return full;
        }
        assertNotNull(summary, name + ": the full load read a sidecar, the summary did not");
        assertFalse(summary == ModuleSidecar.UNREADABLE || summary == ModuleSidecar.FUTURE_VERSION,
            name + ": the full load read a sidecar, the summary reached a sentinel");
        assertEquals(full.getModuleId(), summary.getModuleId(), name + " moduleId");
        assertEquals(full.getModulePath(), summary.getModulePath(), name + " modulePath");
        assertEquals(full.getElementIds(), summary.getElementIds(), name + " element ids");
        return full;
    }

    private static String printable(String ending) {
        return ending.replace("\r", "CR").replace("\n", "LF");
    }
}
