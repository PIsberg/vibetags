package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.GuardrailModelBuilder;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code llms.txt} and {@code llms-full.txt} byte for byte, recorded from {@link LlmsRenderer} before
 * its 44 hand-written sections became a table.
 *
 * <p>{@code LlmsSectionHeaderVariantsTest} pins each section's two headings. What it cannot see is
 * the file around them: the order of the sections, which blank lines separate them (#730), whether
 * an empty section drops out, and the header. A table-driven rewrite gets every one of those wrong
 * in a way a heading check passes, so this pins the whole rendering of 49 models in both files: the
 * empty model, the three every-annotation fixtures, every annotation but the two whose headings
 * have no leading newline, and each of the 44 annotations on its own. The digests live in
 * {@code llms-renderer-golden.sha256} beside this class; a mismatch writes the actual rendering to
 * {@code target/llms-golden/} so it can be read and diffed. The readable tests below state the
 * properties the digests stand for, so a failure says which one broke.
 */
class LlmsRendererGoldenTest {

    static final RenderingContext CONTEXT = new RenderingContext("Golden Project", "# Generated Header\n",
        Set.of("llms", "llms_full"));

    /** Every model the golden digests cover, by case name, in a stable order. */
    static Map<String, Supplier<GuardrailModel>> cases() {
        Map<String, Supplier<GuardrailModel>> cases = new LinkedHashMap<>();
        cases.put("empty", () -> GuardrailModel.EMPTY);
        cases.put("everyAnnotation", GuardrailModels::everyAnnotation);
        cases.put("everyAnnotationWithMembersUnset", GuardrailModels::everyAnnotationWithMembersUnset);
        cases.put("everyAnnotationWithOptionalMembersUnset", GuardrailModels::everyAnnotationWithOptionalMembersUnset);
        cases.put("allButLockedAndContext", () -> {
            GuardrailModelBuilder b = new GuardrailModelBuilder();
            for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
                if (type != AILocked.class && type != AIContext.class) {
                    b.add(type, GuardrailModels.element(type));
                }
            }
            return b.build();
        });
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            cases.put("only:" + type.getSimpleName(),
                () -> new GuardrailModelBuilder().add(type, GuardrailModels.element(type)).build());
        }
        return cases;
    }

    static String render(Platform platform, GuardrailModel model) {
        return PlatformRendererRegistry.getRenderer(platform).render(model, platform, CONTEXT);
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> sectionHeadings(String rendered) {
        return rendered.lines().filter(l -> l.startsWith("## ")).toList();
    }

    private static final List<Platform> PLATFORMS = List.of(Platform.LLMS, Platform.LLMS_FULL);
    private static final String DASH = "—";

    // ------------------------------------------------------------------ golden digests

    static Stream<Arguments> golden() throws IOException {
        List<Arguments> out = new ArrayList<>();
        try (InputStream in = LlmsRendererGoldenTest.class.getResourceAsStream("llms-renderer-golden.sha256")) {
            assertNotNull(in, "llms-renderer-golden.sha256 is missing from the test resources");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                out.add(Arguments.of(parts[0], parts[1], parts[2]));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("golden")
    @DisplayName("renders exactly what it rendered before")
    void rendersExactlyWhatItRenderedBefore(String serviceKey, String caseName, String expectedDigest)
            throws IOException {
        Platform platform = Platform.fromServiceKey(serviceKey);
        assertNotNull(platform, serviceKey);
        Supplier<GuardrailModel> model = cases().get(caseName);
        assertNotNull(model, "no case named " + caseName + " in cases()");
        String rendered = render(platform, model.get());
        String actual = sha256(rendered);
        if (!expectedDigest.equals(actual)) {
            Path dump = Path.of("target", "llms-golden", serviceKey + "-" + caseName.replace(':', '-') + ".txt");
            Files.createDirectories(dump.getParent());
            Files.writeString(dump, rendered, StandardCharsets.UTF_8);
            assertEquals(expectedDigest, actual, serviceKey + " " + caseName + " changed; the rendering is in " + dump);
        }
    }

    @Test
    @DisplayName("every case has a digest for both files, and nothing else does")
    void everyCaseHasADigest() throws IOException {
        List<String> recorded = golden().map(a -> a.get()[0] + " " + a.get()[1]).toList();
        List<String> expected = new ArrayList<>();
        for (Platform platform : PLATFORMS) {
            for (String name : cases().keySet()) {
                expected.add(platform.getServiceKey() + " " + name);
            }
        }
        assertEquals(expected, recorded, "a case added to cases() needs its digests recorded, and a removed one its lines dropped");
    }

    // ------------------------------------------------------------------ the header

    @Test
    void anEmptyModelIsTheCompactHeaderAlone() {
        assertEquals("# Golden Project\n\n"
            + "> AI guardrail rules generated from source annotations by VibeTags.\n\n"
            + "AI tools reading this file should respect the guardrails defined below. These rules were"
            + " extracted from Java source annotations at compile time and apply to all AI assistants including"
            + " Windsurf Cascade, Cursor, Claude, GitHub Copilot, and Gemini.\n\n",
            render(Platform.LLMS, GuardrailModel.EMPTY));
    }

    @Test
    void anEmptyModelIsTheFullHeaderAlone() {
        assertEquals("# Golden Project " + DASH + " AI Guardrail Rules\n"
            + "> Complete AI guardrail configuration generated from source annotations by VibeTags.\n\n"
            + "This document contains the full set of AI guardrail rules for this project. AI tools with large"
            + " context windows (such as Windsurf Cascade, Claude 4.6, or Gemini 1.5 Pro) may load this file"
            + " directly instead of fetching individual documentation pages.\n\n",
            render(Platform.LLMS_FULL, GuardrailModel.EMPTY));
    }

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"LLMS", "LLMS_FULL"})
    void everyRenderingStartsWithTheHeader(Platform platform) {
        String header = render(platform, GuardrailModel.EMPTY);
        for (Map.Entry<String, Supplier<GuardrailModel>> c : cases().entrySet()) {
            assertTrue(render(platform, c.getValue().get()).startsWith(header), c.getKey());
        }
    }

    // ------------------------------------------------------------------ section order

    private static final List<String> COMPACT_ORDER = List.of(
        "## Locked Files",
        "## Contextual Rules",
        "## Security Audit Requirements",
        "## Ignored Elements",
        "## Implementation Tasks",
        "## PII / Privacy Guardrails",
        "## 🧠 Core Functionality",
        "## ⚡ Performance Constraints",
        "## 🔐 Contract-Frozen Signatures",
        "## 🧪 Test-Driven Requirements",
        "## 🧵 Thread-Safe by Design",
        "## ❄️ Immutable Types",
        "## ⚠️ Deprecated Elements",
        "## 📡 Observability Instrumentation",
        "## 📜 Regulatory Compliance",
        "## Strict Test Isolation",
        "## Legacy Compatibility Bridge",
        "## Architectural Boundary Constraints",
        "## Public API Surface Protection",
        "## Strict Exception Handling",
        "## Strict Type Safety",
        "## Internationalization Mandate",
        "## Strict Classpath Integrity",
        "## Schema & Serialization Safety",
        "## ♻️ Idempotency Guarantees",
        "## 🚩 Feature Flag Gated Code",
        "## 🔐 Security-Critical Code",
        "## Access Limitations",
        "## Sandbox & Test Exclusion",
        "## Memory Allocation Budgets",
        "## Deterministic Pure Functions",
        "## Framework-Free Domain Entities",
        "## open-closed Extension Patterns",
        "## Mandatory Input Sanitization",
        "## Secure Logging Masking",
        "## Required Chain-of-Thought Explanations",
        "## Experimental Prototype Stubs",
        "## Sunset Deprecated APIs",
        "## Temporary Code Workarounds",
        "## Generated Code — Edit The Source",
        "## Load-Bearing Oddities",
        "## Banned APIs",
        "## Thread Affinity (Not Thread-Safe)",
        "## Mirrored Elements");

    private static final List<String> FULL_ORDER = List.of(
        "## Locked Files (Do Not Edit)",
        "## Contextual Rules",
        "## Mandatory Security Audit Requirements",
        "## Ignored Elements",
        "## Implementation Tasks",
        "## PII / Privacy Guardrails",
        "## 🧠 Core Functionality",
        "## ⚡ Performance Constraints",
        "## 🔐 Contract-Frozen Signatures",
        "## 🧪 Test-Driven Requirements",
        "## 🧵 Thread-Safe by Design",
        "## ❄️ Immutable Types",
        "## ⚠️ Deprecated Elements",
        "## 📡 Observability Instrumentation",
        "## 📜 Regulatory Compliance",
        "## Strict Test Isolation",
        "## Legacy Compatibility Bridge",
        "## Architectural Boundary Constraints",
        "## Public API Surface Protection",
        "## Strict Exception Handling",
        "## Strict Type Safety",
        "## Internationalization Mandate",
        "## Strict Classpath Integrity",
        "## Schema & Serialization Safety",
        "## ♻️ Idempotency Guarantees",
        "## 🚩 Feature Flag Gated Code",
        "## 🔐 Security-Critical Code",
        "## Access Limitations",
        "## Sandbox & Test Exclusion",
        "## Memory Allocation Budgets",
        "## Deterministic Pure Functions",
        "## Framework-Free Domain Entities",
        "## open-closed Extension Patterns",
        "## Mandatory Input Sanitization",
        "## Secure Logging Masking",
        "## Required Chain-of-Thought Explanations",
        "## Experimental Prototype Stubs",
        "## Sunset Deprecated APIs",
        "## Temporary Code Workarounds",
        "## Generated Code — Edit The Source",
        "## Load-Bearing Oddities",
        "## Banned APIs",
        "## Thread Affinity (Not Thread-Safe)",
        "## Mirrored Elements");

    @Test
    void theCompactFileListsEverySectionInItsFixedOrder() {
        assertEquals(COMPACT_ORDER, sectionHeadings(render(Platform.LLMS, GuardrailModels.everyAnnotation())));
    }

    @Test
    void theFullFileListsEverySectionInItsFixedOrder() {
        assertEquals(FULL_ORDER, sectionHeadings(render(Platform.LLMS_FULL, GuardrailModels.everyAnnotation())));
    }

    @Test
    void bothFilesHaveOneSectionPerAnnotation() {
        assertEquals(GuardrailAnnotations.ALL.size(), COMPACT_ORDER.size());
        assertEquals(GuardrailAnnotations.ALL.size(), FULL_ORDER.size());
        assertEquals(COMPACT_ORDER.size(), Set.copyOf(COMPACT_ORDER).size(), "compact headings are unique");
        assertEquals(FULL_ORDER.size(), Set.copyOf(FULL_ORDER).size(), "full headings are unique");
    }

    // ------------------------------------------------------------------ one annotation at a time

    static Stream<Arguments> singles() {
        List<Arguments> out = new ArrayList<>();
        for (Platform platform : PLATFORMS) {
            for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
                out.add(Arguments.of(platform, type));
            }
        }
        return out.stream();
    }

    private static GuardrailModel only(Class<? extends Annotation> type) {
        return new GuardrailModelBuilder().add(type, GuardrailModels.element(type)).build();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("singles")
    void oneAnnotationRendersTheHeaderAndExactlyOneSection(Platform platform, Class<? extends Annotation> type) {
        String rendered = render(platform, only(type));
        assertEquals(1, sectionHeadings(rendered).size(), rendered);
        assertTrue(rendered.contains(GuardrailModels.marker(type)), "the element is named under its section");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("singles")
    void aSectionNeverOpensWithTwoBlankLines(Platform platform, Class<? extends Annotation> type) {
        assertFalse(render(platform, only(type)).contains("\n\n\n"),
            "a skipped section must not leave its blank line behind (#730)");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("singles")
    void theSectionIsTheOneThisAnnotationOwns(Platform platform, Class<? extends Annotation> type) {
        List<String> order = platform == Platform.LLMS ? COMPACT_ORDER : FULL_ORDER;
        String heading = sectionHeadings(render(platform, only(type))).get(0);
        assertTrue(order.contains(heading), heading);
        long owners = GuardrailAnnotations.ALL.stream()
            .filter(other -> sectionHeadings(render(platform, only(other))).contains(heading))
            .count();
        assertEquals(1, owners, heading + " is rendered by exactly one annotation");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("singles")
    void aSectionWhoseEntriesRenderNothingDropsItsHeading(Platform platform, Class<? extends Annotation> type) {
        // With every member unset, some formatters have nothing to say (a bare @AIAudit names no
        // checks). Their heading must go with them rather than announce an empty section (#728).
        GuardrailModel unset = GuardrailModels.everyAnnotationWithMembersUnset();
        GuardrailModel one = new GuardrailModelBuilder().add(type, unset.of(type).iterator().next()).build();
        String rendered = render(platform, one);
        String header = render(platform, GuardrailModel.EMPTY);
        int headings = sectionHeadings(rendered).size();
        assertTrue(headings == 0 ? rendered.equals(header) : headings == 1,
            "either nothing beyond the header, or exactly one section with its entry: " + rendered);
    }

    // ------------------------------------------------------------------ no hidden state

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("singles")
    void renderingIsRepeatableAndIndependentOfTheOtherFile(Platform platform, Class<? extends Annotation> type) {
        String first = render(platform, only(type));
        render(platform == Platform.LLMS ? Platform.LLMS_FULL : Platform.LLMS, GuardrailModels.everyAnnotation());
        assertEquals(first, render(platform, only(type)));
    }

    @Test
    void theCompactFileIsNeverLongerThanTheFullOne() {
        for (Map.Entry<String, Supplier<GuardrailModel>> c : cases().entrySet()) {
            GuardrailModel model = c.getValue().get();
            assertTrue(render(Platform.LLMS, model).length() <= render(Platform.LLMS_FULL, model).length(), c.getKey());
        }
    }

    @Test
    void everyRenderingEndsWithANewlineAndHasNoTrailingSpaces() {
        Pattern trailing = Pattern.compile("[ \t]+$", Pattern.MULTILINE);
        for (Platform platform : PLATFORMS) {
            for (Map.Entry<String, Supplier<GuardrailModel>> c : cases().entrySet()) {
                String rendered = render(platform, c.getValue().get());
                assertTrue(rendered.endsWith("\n"), platform + " " + c.getKey());
                assertFalse(trailing.matcher(rendered).find(), platform + " " + c.getKey());
            }
        }
    }
}
