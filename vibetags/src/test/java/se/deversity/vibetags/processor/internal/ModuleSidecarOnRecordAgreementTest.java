package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cheap answers to {@code init()}'s two questions are the ones the full parse reaches (#858).
 *
 * <p>{@code ModuleSidecar.onRecord} streams each sidecar past and keeps only what the two
 * questions read: the headers, the element ids, and whether an unrouted body is blank. The full
 * {@code load} is the oracle. Every case asserts one of two relations rather than a hand-copied
 * expectation, so a scan that drifts from {@code load} fails on the shape that made it drift:
 *
 * <ul>
 *   <li>per file, {@code loadSummary} reaches the same verdict as {@code load} (the same sentinel,
 *       {@code null}, or a sidecar with the same identity, element ids and unrouted blankness);</li>
 *   <li>per root, {@code onRecord} answers what the two questions answer over {@code peekAll},
 *       which also runs the stale-path and superseded-region filtering the summaries go through.</li>
 * </ul>
 *
 * <p>The answers only widen what {@code getSupportedAnnotationTypes()} claims, so a disagreement in
 * one direction would cost a spurious round and in the other a sidecar that keeps contributing a
 * removed guardrail for good (#781). Neither is acceptable as a silent side effect of a speed-up.
 */
class ModuleSidecarOnRecordAgreementTest {

    @TempDir
    Path root;

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Path save(ModuleSidecar sidecar) throws IOException {
        Files.createDirectories(root.resolve(sidecar.getModulePath()));
        sidecar.save(root);
        return root.resolve(ModuleSidecar.SIDECAR_PREFIX + sidecar.getModuleId());
    }

    private Path write(String moduleId, String content) throws IOException {
        Files.createDirectories(root.resolve(moduleId));
        Path path = root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static boolean anyNonBlank(ModuleSidecar s) {
        return s.getUnroutedBodies().values().stream().anyMatch(v -> !v.isBlank());
    }

    /** Asserts the per-file relation and returns the full load's verdict for the case to pin. */
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
        assertEquals(full.getRegionId(), summary.getRegionId(), name + " regionId");
        assertEquals(full.getElementIds(), summary.getElementIds(), name + " element ids");
        assertEquals(anyNonBlank(full), anyNonBlank(summary), name + " unrouted body blankness");
        return full;
    }

    /** Asserts the per-root relation and returns the pair of answers for the case to pin. */
    private ModuleSidecar.OnRecord agreesOnRoot() {
        List<ModuleSidecar> full = ModuleSidecar.peekAll(root, null);
        ModuleSidecar.OnRecord expected = new ModuleSidecar.OnRecord(
            ModuleSidecar.anyRecordsElements(full),
            ModuleSidecar.holdsWithdrawnTestingFallback(root, full));
        ModuleSidecar.OnRecord actual = ModuleSidecar.onRecord(root);
        assertEquals(expected, actual, "onRecord must answer what the full parse answers");
        assertEquals(expected.recordsElements(), ModuleSidecar.anyRecordsElements(root));
        assertEquals(expected.withdrawnTestingFallback(), ModuleSidecar.holdsWithdrawnTestingFallback(root));
        return actual;
    }

    @Test
    @DisplayName("no sidecar: nothing on record")
    void emptyRoot() {
        assertEquals(new ModuleSidecar.OnRecord(false, false), agreesOnRoot());
    }

    @Test
    @DisplayName("element ids are read, and a sidecar with none records nothing")
    void elementIds() throws IOException {
        ModuleSidecar bare = new ModuleSidecar("bare", "bare");
        bare.putBody("claude", "a body");
        agreesPerFile(save(bare));
        assertEquals(new ModuleSidecar.OnRecord(false, false), agreesOnRoot());

        ModuleSidecar tagged = new ModuleSidecar("tagged", "tagged");
        tagged.putBody("claude", "a body");
        tagged.setElementIds(Set.of("com.example.A", "com.example.B#run()"));
        agreesPerFile(save(tagged));
        assertEquals(new ModuleSidecar.OnRecord(true, false), agreesOnRoot());
    }

    @Test
    @DisplayName("an element list that decodes to blank lines only records nothing")
    void blankElementLines() throws IOException {
        agreesPerFile(write("blank", "# version=3\nmoduleId=blank\nmodulePath=blank\n~elements="
            + b64("\n  \n\t") + "\n# end\n"));
        assertFalse(agreesOnRoot().recordsElements());
    }

    @Test
    @DisplayName("a non-blank unrouted body is a pending fallback only while TESTING.md is absent")
    void unroutedBodyAndTheOptIn() throws IOException {
        ModuleSidecar tests = new ModuleSidecar("core__test", "core", "core");
        tests.putBody("claude", "routed half");
        tests.putUnroutedBody("claude", "the whole body");
        agreesPerFile(save(tests));
        assertEquals(new ModuleSidecar.OnRecord(false, true), agreesOnRoot());

        Files.createFile(root.resolve("TESTING.md"));
        assertEquals(new ModuleSidecar.OnRecord(false, false), agreesOnRoot());
    }

    @ParameterizedTest(name = "unrouted body [{index}] is judged blank exactly as String.isBlank judges it")
    @ValueSource(strings = {
        "", " ", "\n\t \r", "\u3000\u2003\u1680\u2028\u205F", "\u00A0", "\u200B", "\u00E9",
        "     x", "\u3000x", "\uD83D\uDE00", "\u2007", "\u2008\u2009\u200A"
    })
    void unroutedBlankness(String unrouted) throws IOException {
        // Written by hand: putUnroutedBody drops a blank body, so the writer alone never puts one on
        // disk, while an older writer, a hand edit or a torn merge can.
        Path sidecar = write("core", "# version=3\nmoduleId=core\nmodulePath=core\nclaude=" + b64("routed half")
            + "\n~tfull~claude=" + b64(unrouted) + "\n# end\n");
        ModuleSidecar full = agreesPerFile(sidecar);
        assertEquals(Set.of("claude"), full.getUnroutedBodies().keySet(), "fixture assumption");
        assertEquals(!unrouted.isBlank(), anyNonBlank(full), "fixture assumption");
        assertEquals(!unrouted.isBlank(), agreesOnRoot().withdrawnTestingFallback());
    }

    @Test
    @DisplayName("the last value for a service decides, as the full load's map keeps the last put")
    void repeatedUnroutedKeyLastWins() throws IOException {
        write("core", "# version=3\nmoduleId=core\nmodulePath=core\n~tfull~claude=" + b64("body")
            + "\n~tfull~claude=" + b64("  ") + "\n# end\n");
        assertFalse(agreesOnRoot().withdrawnTestingFallback());
        write("core", "# version=3\nmoduleId=core\nmodulePath=core\n~tfull~claude=" + b64("  ")
            + "\n~tfull~cursor=" + b64("body") + "\n# end\n");
        assertTrue(agreesOnRoot().withdrawnTestingFallback());
    }

    @ParameterizedTest(name = "encoded value [{0}] is accepted or refused exactly as the decoder does")
    @ValueSource(strings = {
        "", "x", "=", "==", "eA", "eA=", "eA==", "eA===", "eHh4", "eHh=", "e===", "eA==eA",
        "eHh4eA", "eHh4e", "not-base-64!", "a_b-", "eA==\u00E9", "eHh4\u00E9", "eA= =", " eA==",
        "eHh4eHh4eHh4eHh4eHh4eHh4eHh4", "eHh4eHh4eHh4eHh4eHh4eHh4eHh=", "eHh4eHh4eHh4eHh4eHh4eHh4eH=="
    })
    void base64ValidityMatchesTheDecoder(String encoded) throws IOException {
        for (String key : everyKeyShape()) {
            Path sidecar = write("core", "# version=3\nmoduleId=core\nmodulePath=core\n"
                + key + "=" + encoded + "\n# end\n");
            agreesPerFile(sidecar);
            agreesOnRoot();
        }
    }

    /**
     * One key of every shape the format defines, read off its {@code KEY_*} constants rather than
     * copied here, so a key added to {@code load} later is checked without anyone remembering to add
     * it: the summary has to classify it the same way or a corrupt value of it splits the verdicts.
     * Plus a service key, which is no constant, and a reserved key no version defines.
     */
    private static List<String> everyKeyShape() {
        List<String> keys = new ArrayList<>(List.of("claude", "~unknown~future"));
        for (java.lang.reflect.Field field : ModuleSidecar.class.getDeclaredFields()) {
            if (!field.getName().startsWith("KEY_") || field.getType() != String.class
                    || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            String value;
            try {
                value = (String) field.get(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            if (value.startsWith("#")) {
                continue; // the version header is a comment line, covered by everyVerdict
            }
            keys.add(field.getName().endsWith("_PREFIX") ? value + "claude" : value);
        }
        assertTrue(keys.size() >= 14, "the reflection found too few keys to be reading the format: " + keys);
        return keys;
    }

    @Test
    @DisplayName("every verdict of the full load is reached by the summary too")
    void everyVerdict() throws IOException {
        assertSame(ModuleSidecar.FUTURE_VERSION,
            agreesPerFile(write("future", "# version=99999\nmoduleId=future\n# end\n")));
        assertNull(agreesPerFile(write("stale", "# version=1\nmoduleId=stale\n# end\n")));
        assertNull(agreesPerFile(write("odd", "# version=three\nmoduleId=odd\n# end\n")));
        assertNull(agreesPerFile(write("bare", "moduleId=bare\n# end\n")));
        assertNull(agreesPerFile(write("anon", "# version=3\nmodulePath=anon\n# end\n")));
        assertSame(ModuleSidecar.UNREADABLE,
            agreesPerFile(write("torn", "# version=3\nmoduleId=torn\n~elements=" + b64("A") + "\n")));
        assertNotNull(agreesPerFile(write("old", "# version=2\nmoduleId=old\nmodulePath=old\n~elements="
            + b64("A") + "\n")));
        assertNotNull(agreesPerFile(write("crlf", "# version=3\r\nmoduleId=crlf\r\nmodulePath=crlf\r\n"
            + "~elements=" + b64("A\nB") + "\r\n~tfull~claude=" + b64("body") + "\r\n# end\r\n")));
        assertNotNull(agreesPerFile(write("cr", "# version=3\rmoduleId=cr\rmodulePath=cr\r~elements="
            + b64("A") + "\r# end")));
        assertNotNull(agreesPerFile(write("region", "# version=3\nmoduleId=region__test\nmodulePath=region\n"
            + "regionId=region\n# end\n")));
        assertNotNull(agreesPerFile(write("blankregion", "# version=3\nmoduleId=blankregion\n"
            + "modulePath=blankregion\nregionId=  \n# end\n")));
        assertNotNull(agreesPerFile(write("indented", "# version=3\nmoduleId=indented\n  # version=99\n"
            + "modulePath=indented\n   # end  \n")));
        assertSame(ModuleSidecar.FUTURE_VERSION, agreesPerFile(write("late",
            "# version=3\nmoduleId=late\n# version=4\n# end\n")));
        assertNull(agreesPerFile(write("badfirst", "# version=3\nmoduleId=badfirst\nclaude=!!\n"
            + "# version=99\n# end\n")), "the first refusal in file order decides, as it does in load");
        agreesOnRoot();
    }

    @Test
    @DisplayName("bytes that are not UTF-8 make the file unreadable, even behind a future version")
    void invalidUtf8() throws IOException {
        for (String head : List.of("# version=3\nmoduleId=moj\nclaude=", "# version=99\nmoduleId=moj\nclaude=")) {
            Files.createDirectories(root.resolve("moj"));
            Path sidecar = root.resolve(ModuleSidecar.SIDECAR_PREFIX + "moj");
            byte[] start = head.getBytes(StandardCharsets.UTF_8);
            byte[] end = "\n# end\n".getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[start.length + 2 + end.length];
            System.arraycopy(start, 0, all, 0, start.length);
            all[start.length] = (byte) 0xC3;
            all[start.length + 1] = (byte) 0x28;
            System.arraycopy(end, 0, all, start.length + 2, end.length);
            Files.write(sidecar, all);
            assertSame(ModuleSidecar.UNREADABLE, agreesPerFile(sidecar));
            agreesOnRoot();
        }
    }

    @Test
    @DisplayName("a sidecar whose module directory is gone is left out of both answers")
    void staleModulePath() throws IOException {
        Path sidecar = write("gone", "# version=3\nmoduleId=gone\nmodulePath=gone\n~elements=" + b64("A")
            + "\n~tfull~claude=" + b64("body") + "\n# end\n");
        Files.delete(root.resolve("gone"));
        assertNotNull(agreesPerFile(sidecar));
        assertEquals(new ModuleSidecar.OnRecord(false, false), agreesOnRoot());
        assertTrue(Files.exists(sidecar), "a question asked before any round must not prune");
    }

    @Test
    @DisplayName("a superseded region is left out of both answers, as the full read leaves it out")
    void supersededRegion() throws IOException {
        // The reactor root read the app module's sources under a less specific identity: every
        // element it claims is also claimed by the nested region, which knows one more. Only the
        // stale root region holds an unrouted body, so the fallback answer depends on the drop.
        Files.createDirectories(root.resolve("app"));
        Path stale = write("_root_x", "# version=3\nmoduleId=_root_x\nmodulePath=\n~elements=" + b64("A")
            + "\n~tfull~claude=" + b64("stale body") + "\n# end\n");
        Path fresh = write("app", "# version=3\nmoduleId=app\nmodulePath=app\n~elements=" + b64("A\nB")
            + "\n# end\n");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(2_000_000_000_000L));
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(1_000_000_000_000L));

        assertEquals(new ModuleSidecar.OnRecord(true, false), agreesOnRoot(),
            "fixture assumption: the full read drops the root region, and its fallback with it");
    }

    @Test
    @DisplayName("the fixtures above are not silently all-agreeing on false: a case of each answer exists")
    void fixturesReachBothAnswers() throws IOException {
        List<ModuleSidecar.OnRecord> seen = new ArrayList<>();
        seen.add(agreesOnRoot());
        ModuleSidecar tests = new ModuleSidecar("core__test", "core", "core");
        tests.putUnroutedBody("claude", "body");
        tests.setElementIds(Set.of("A"));
        save(tests);
        seen.add(agreesOnRoot());
        assertEquals(List.of(new ModuleSidecar.OnRecord(false, false), new ModuleSidecar.OnRecord(true, true)),
            seen);
    }
}
