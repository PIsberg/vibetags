package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Annotation values are prose, and prose is not ASCII.
 *
 * <p>A guardrail reason is written by whoever owns the code, in whatever language the team works
 * in. "Får inte loggas enligt GDPR §9" is an ordinary {@code @AIPrivacy} reason in a Swedish
 * codebase, and the rule is worthless if it reaches the agent as "F?r inte loggas".
 *
 * <p>Nothing in the processor decodes bytes — javac hands it {@code String}s and every write is
 * explicitly UTF-8 — so the risk is not the characters but what the per-format escaping does to
 * them. Each renderer escapes for its own syntax, and an escaper written against ASCII is exactly
 * the kind of thing that drops a code point above U+007F or splits a surrogate pair. So this drives
 * a real compile and reads the values back out of each format.
 *
 * <p>Every sample rides on a single {@code @AILocked} reason rather than one annotation each. That
 * is deliberate: which sections a given platform renders is a separate question with its own tests,
 * and threading the samples through eight annotations would make this fail for reasons that have
 * nothing to do with encoding. One value that every format is known to carry isolates the variable.
 *
 * <p>The samples deliberately contain no characters that any of these formats must escape — see
 * {@link #escapesMetacharactersWithoutTouchingTheNonAsciiAroundThem} for that half, which is a
 * different property and would otherwise hide behind this one.
 */
@DisplayName("International characters in annotation values")
@Tag("e2e")
class InternationalCharactersEndToEndTest {

    /**
     * One sample per script family. None contains a quote, apostrophe, backslash, or angle bracket,
     * so a failure here means a lost character rather than a correctly escaped one.
     */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("swedish", "Får inte loggas enligt GDPR §9");
        SAMPLES.put("german", "Straße für Zahlungsabwicklung");
        SAMPLES.put("french", "Réservé à toute équipe");
        SAMPLES.put("cjk", "支付网关配置");
        SAMPLES.put("japanese", "個人情報を記録しないこと");
        SAMPLES.put("cyrillic", "Платёжный шлюз");
        SAMPLES.put("greek", "Κρυπτογράφηση απαιτείται");
        SAMPLES.put("arabic", "لا تسجل بيانات الدفع");
        SAMPLES.put("hebrew", "אין לתעד פרטי תשלום");
        // Outside the BMP: two chars each, so any escaper stepping char by char splits them.
        SAMPLES.put("emoji", "🔒 locked 🇸🇪 payment 𝕍ibe");
        SAMPLES.put("combining", "égalité — decomposed acute");
    }

    /** Every sample in one value, which is what the annotation below actually carries. */
    private static final String ALL_SAMPLES = String.join(" / ", SAMPLES.values());

    @AfterEach
    void shutdownLogger() {
        VibeTagsLogger.shutdown();
    }

    @Test
    @DisplayName("survive into the XML-shaped aggregate")
    void survivesIntoClaudeMd(@TempDir Path dir) throws IOException {
        ProcessorTestHarness harness = compileWithSamples(dir, "CLAUDE.md");
        assertAllSamplesPresent(harness.readFile("CLAUDE.md"), "CLAUDE.md");
    }

    @Test
    @DisplayName("survive into JSON, and the document still parses")
    void survivesIntoMentatJson(@TempDir Path dir) throws IOException {
        ProcessorTestHarness harness = compileWithSamples(dir, ".mentatconfig.json");
        String out = harness.readFile(".mentatconfig.json");

        // Parsed, not string-matched: a renderer emitting a raw control character or a truncated
        // \\u escape would still "contain" the text while being unreadable to the tool.
        String flattened = String.valueOf(parseJson(out));
        assertAllSamplesPresent(flattened, ".mentatconfig.json (after parsing)");
    }

    @Test
    @DisplayName("survive into TOML")
    void survivesIntoPrAgentToml(@TempDir Path dir) throws IOException {
        ProcessorTestHarness harness = compileWithSamples(dir, ".pr_agent.toml");
        assertAllSamplesPresent(harness.readFile(".pr_agent.toml"), ".pr_agent.toml");
    }

    @Test
    @DisplayName("survive the base64 sidecar round trip between modules")
    void survivesTheSidecarRoundTrip(@TempDir Path dir) throws IOException {
        // The sidecar is how one module's rendering reaches another's merge, and it base64-encodes
        // each body. Encode with one charset and decode with another and every sample here breaks,
        // but only in a reactor — the build nobody runs locally before pushing.
        Files.createDirectories(dir.resolve("module-a"));
        Files.createDirectories(dir.resolve("module-b"));
        Files.createFile(dir.resolve("CLAUDE.md"));

        compileModule(dir, "module-a", "com.example.a", "Alpha");
        compileModule(dir, "module-b", "com.example.b", "Beta");

        String merged = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertAllSamplesPresent(merged, "the merged reactor CLAUDE.md");
        assertTrue(merged.contains("Alpha") && merged.contains("Beta"),
            "both modules should have reached the merged file, or the round trip was not exercised");
    }

    @Test
    @DisplayName("a value read from a UTF-8 source file is not mangled while decoding")
    void survivesFromAFileBackedSource(@TempDir Path dir) throws IOException {
        // The tests above hand javac in-memory Strings, which cannot exercise decoding at all. A
        // consumer compiles files from disk, so this writes UTF-8 bytes and lets the compiler
        // decode them — the step where a platform default charset would do the damage.
        ProcessorTestHarness harness = new ProcessorTestHarness(dir, false);
        harness.touchOptIn("CLAUDE.md");
        harness.writeSourceFile("src/main/java/com/example/FromFile.java",
            sourceFor("com.example", "FromFile"));
        harness.compile();

        assertAllSamplesPresent(harness.readFile("CLAUDE.md"), "CLAUDE.md (file-backed source)");
    }

    @Test
    @DisplayName("the generated file is written as UTF-8 whatever the platform default is")
    void generatedBytesAreUtf8(@TempDir Path dir) throws IOException {
        ProcessorTestHarness harness = compileWithSamples(dir, "CLAUDE.md");
        byte[] bytes = Files.readAllBytes(harness.root().resolve("CLAUDE.md"));

        String decoded = new String(bytes, StandardCharsets.UTF_8);
        assertAllSamplesPresent(decoded, "CLAUDE.md decoded as UTF-8");

        // Multi-byte content must make the byte count exceed the char count. Without this the test
        // would still pass if every sample were replaced by ASCII in both the file and the samples.
        assertTrue(bytes.length > decoded.length(),
            "the samples are multi-byte, so the UTF-8 encoding must be longer than the character "
                + "count; it was not, so the content reaching disk is pure ASCII");

        // U+FFFD is what a lossy decode leaves behind; it must never appear.
        assertFalse(decoded.contains("�"),
            "the generated file contains U+FFFD REPLACEMENT CHARACTER, which means something in "
                + "the pipeline decoded bytes with the wrong charset");
    }

    @Test
    @DisplayName("metacharacters are escaped without disturbing the non-ASCII around them")
    void escapesMetacharactersWithoutTouchingTheNonAsciiAroundThem(@TempDir Path dir)
            throws IOException {
        // The other half of the story. An escaper has to act on < > & " ' and leave everything
        // above U+007F alone; getting either side wrong is a different defect from a lost
        // character, and asserting them together would let one hide the other.
        ProcessorTestHarness harness = new ProcessorTestHarness(dir, false);
        harness.touchOptIn("CLAUDE.md");
        harness.addSource("com.example.Meta",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Meta {\n"
                + "    @AILocked(reason = \"Räkna <taggar> & \\\"citat\\\" för l'équipe 支付\")\n"
                + "    public void betala() {}\n"
                + "}\n");
        harness.compile();
        String out = harness.readFile("CLAUDE.md");

        // The XML metacharacters must not appear raw inside the reason.
        String reason = between(out, "<reason>", "</reason>");
        assertFalse(reason.contains("<taggar>"),
            "angle brackets reached the XML unescaped, which breaks the document: " + reason);
        assertTrue(reason.contains("&lt;") && reason.contains("&gt;") && reason.contains("&amp;"),
            "expected escaped <, > and & in the reason: " + reason);
        // ...while every non-ASCII character passes through untouched.
        assertTrue(reason.contains("Räkna") && reason.contains("för") && reason.contains("支付"),
            "escaping mangled the non-ASCII characters around the metacharacters: " + reason);
    }

    // -----------------------------------------------------------------------

    private static String between(String haystack, String open, String close) {
        int i = haystack.indexOf(open);
        int j = i < 0 ? -1 : haystack.indexOf(close, i);
        return i < 0 || j < 0 ? "" : haystack.substring(i + open.length(), j);
    }

    /** JSON is a subset of YAML, and this is the parser the merge tests already use. */
    private static Object parseJson(String json) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            return new Yaml(options).load(json);
        } catch (RuntimeException e) {
            throw new AssertionError("not a valid JSON document: " + e.getMessage()
                + "\n---- content ----\n" + json, e);
        }
    }

    private static void assertAllSamplesPresent(String content, String what) {
        List<String> missing = new ArrayList<>();
        SAMPLES.forEach((name, sample) -> {
            if (!content.contains(sample)) {
                missing.add(name + " → expected \"" + sample + "\"");
            }
        });
        assertTrue(missing.isEmpty(),
            "These annotation values did not survive into " + what + ":\n  "
                + String.join("\n  ", missing) + "\n--- actual ---\n" + content);
    }

    private static ProcessorTestHarness compileWithSamples(Path dir, String optInFile)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(dir, false);
        harness.touchOptIn(optInFile);
        harness.addSource("com.example.Intl", sourceFor("com.example", "Intl"));
        harness.compile();
        return harness;
    }

    /**
     * One {@code @AILocked} carrying every sample. {@code @AILocked} is used because its reason is
     * free prose that every one of these formats renders — the point here is the characters, not
     * the section layout.
     */
    private static String sourceFor(String pkg, String className) {
        return "package " + pkg + ";\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "public class " + className + " {\n"
            + "    @AILocked(reason = \"" + ALL_SAMPLES + "\")\n"
            + "    public void betala() {}\n"
            + "}\n";
    }

    /** One module's compile into a shared reactor root, as a reactor pass would do it. */
    private static void compileModule(Path root, String module, String pkg, String className)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(
            module + "/src/main/java/" + pkg.replace('.', '/') + "/" + className + ".java",
            sourceFor(pkg, className));
        harness.compile();
        VibeTagsLogger.shutdown();
    }
}
