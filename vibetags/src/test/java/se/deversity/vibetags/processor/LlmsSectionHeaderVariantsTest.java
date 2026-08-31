package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.platforms.LlmsRenderer;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code llms.txt} and {@code llms-full.txt} share one renderer whose 44 sections each choose
 * between a bare heading and a heading followed by an explanatory sentence, via a
 * {@code full ? long : short} ternary per section.
 *
 * <p>The mutation report showed all 42 of those ternaries below the first two could be flipped
 * without a failure: the compact file could ship carrying the book-sized explanations, or the
 * full file could lose every explanation, and nothing would notice. Existing tests assert
 * {@code contains("## Heading")}, which is a substring of both variants, so it distinguishes
 * nothing.
 *
 * <p>The table is transcribed from {@link LlmsRenderer} verbatim, in both directions: the full
 * file must carry heading and explanation as consecutive lines, and the compact file must carry
 * the heading without the explanation anywhere. The count check makes section 45 fail the test
 * until its row is added.
 */
class LlmsSectionHeaderVariantsTest {

    // Rendered per test rather than once in a static initializer: PIT attributes line coverage
    // to the test that physically executes the line, and a static field would hand all 44
    // sections' coverage to whichever test happens to load the class first — leaving the other
    // 43 sections' mutants with no covering test to run.
    private static String render(Platform platform) {
        RenderingContext ctx = new RenderingContext("P", "# header\n", Set.of("llms", "llms_full"));
        return new LlmsRenderer().render(GuardrailModels.everyAnnotation(), platform, ctx);
    }

    /** One section whose heading reads the same in both files. */
    private record Row(String heading, String explanation) {
    }

    private static Row row(String heading, String explanation) {
        return new Row(heading, explanation);
    }

    /** The two sections whose full heading is longer than the compact one, pinned separately. */
    private static final List<String[]> RENAMED = List.of(
        new String[]{"Locked Files", "Locked Files (Do Not Edit)",
            "The following files are locked. AI tools MUST NOT propose modifications to them."},
        new String[]{"Security Audit Requirements", "Mandatory Security Audit Requirements",
            "When writing or modifying the following files, perform a security audit for the "
                + "listed vulnerabilities before displaying any code to the user."});

    static Stream<Row> sections() {
        return Stream.of(
            row("Contextual Rules",
                "These files have specific context and focus areas for AI assistance."),
            row("Ignored Elements",
                "The following elements must be completely excluded from AI context. Treat them as non-existent."),
            row("Implementation Tasks",
                "The following elements are in draft mode and need implementation."),
            row("PII / Privacy Guardrails",
                "Never include runtime values of the following elements in logs, console output, external API calls, test fixtures, or mock data."),
            row("🧠 Core Functionality",
                "The following elements are well-tested core functionality. Make changes with extreme caution."),
            row("⚡ Performance Constraints",
                "The following elements are on a hot-path and have strict time/space complexity constraints."),
            row("🔐 Contract-Frozen Signatures",
                "The following elements have frozen public API signatures. Internal implementation may be changed, but you MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions."),
            row("🧪 Test-Driven Requirements",
                "The following elements require a matching test update whenever their logic is modified. Changes without tests are incomplete."),
            row("🧵 Thread-Safe by Design",
                "These elements are explicitly designed to be thread-safe via the named strategy. Preserve the synchronization invariant on every change."),
            row("❄️ Immutable Types",
                "The following types are immutable. Never introduce non-final fields, setters, or mutating methods."),
            row("⚠️ Deprecated Elements",
                "The following elements are deprecated. Suggest migration to the named replacement for any caller and do not extend them."),
            row("📡 Observability Instrumentation",
                "The following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on."),
            row("📜 Regulatory Compliance",
                "The following elements implement specific regulatory clauses. Document compliance impact for every change and never weaken the requirement."),
            row("Strict Test Isolation",
                "AI tools must enforce strict isolation when generating or modifying tests for these elements."),
            row("Legacy Compatibility Bridge",
                "These elements are legacy or compatibility bridges. Do not restructure or modernize them."),
            row("Architectural Boundary Constraints",
                "Strict architectural layering must be respected. No illegal references or imports."),
            row("Public API Surface Protection",
                "These elements expose public API surfaces. Preserve signatures, Javadocs, and backward compatibility."),
            row("Strict Exception Handling",
                "Precise and robust exception handling must be enforced. No catching or throwing generic Exception."),
            row("Strict Type Safety",
                "Type safety must be strictly preserved. Loose or erased types are prohibited."),
            row("Internationalization Mandate",
                "User-facing strings must not be hardcoded; resolve them via localized resources."),
            row("Strict Classpath Integrity",
                "Dynamic runtime class loading and reflections are strictly prohibited."),
            row("Schema & Serialization Safety",
                "Schema and serialization compatibility must be strictly preserved."),
            row("♻️ Idempotency Guarantees",
                "These operations are idempotent — calling multiple times must produce the same result as calling once."),
            row("🚩 Feature Flag Gated Code",
                "These elements are gated behind a feature flag. Preserve the flag check and handle both enabled and disabled code paths."),
            row("🔐 Security-Critical Code",
                "These elements are security-critical. Do not weaken security properties. Every change requires security review."),
            row("Access Limitations",
                "The following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries."),
            row("Sandbox & Test Exclusion",
                "The following elements are strictly sandbox/test code. Production code must never import or reference them."),
            row("Memory Allocation Budgets",
                "The following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully."),
            row("Deterministic Pure Functions",
                "The following elements must remain pure functions without side effects or mutations."),
            row("Framework-Free Domain Entities",
                "The following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages."),
            row("open-closed Extension Patterns",
                "The following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals."),
            row("Mandatory Input Sanitization",
                "The following parameters/fields must go through strict sanitizers before hitting queries or renderers."),
            row("Secure Logging Masking",
                "The following sensitive elements must be masked, hashed, or omitted from log/stdout streams."),
            row("Required Chain-of-Thought Explanations",
                "Any change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough."),
            row("Experimental Prototype Stubs",
                "Strict QA constraints and tests are relaxed for these elements, but production classes must never import them."),
            row("Sunset Deprecated APIs",
                "Strictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden."),
            row("Temporary Code Workarounds",
                "Temporary stubs or hacks that must be refactored or removed before their expiration limit."),
            row("Generated Code — Edit The Source",
                "These elements are machine-generated and hand edits are silently overwritten. Read them freely; never write them."),
            row("Load-Bearing Oddities",
                "These look wrong, redundant, or over-defensive and are deliberate. Refactoring is allowed only while the stated invariant survives."),
            row("Banned APIs",
                "The following APIs compile at these elements but are prohibited there. Use the sanctioned replacement."),
            row("Thread Affinity (Not Thread-Safe)",
                "These elements are safe on exactly one thread. Do not add locks — marshal the call onto the required thread instead."),
            row("Mirrored Elements",
                "These are duplicated elsewhere. They may change freely, but a partial change silently desyncs a mirror no compiler checks."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sections")
    @DisplayName("llms-full.txt explains the section directly under its heading")
    void fullFileCarriesTheExplanation(Row row) {
        assertTrue(render(Platform.LLMS_FULL).contains("## " + row.heading() + "\n" + row.explanation() + "\n\n"),
            "llms-full.txt is the variant a large-context agent loads instead of the docs, and "
                + "its '" + row.heading() + "' section must carry its explanation");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sections")
    @DisplayName("llms.txt keeps the section compact")
    void compactFileCarriesTheBareHeading(Row row) {
        String llms = render(Platform.LLMS);
        assertTrue(llms.contains("## " + row.heading() + "\n"),
            "llms.txt lost the '" + row.heading() + "' heading");
        assertFalse(llms.contains(row.explanation()),
            "llms.txt is the compact discovery file; the '" + row.heading()
                + "' explanation belongs to llms-full.txt only");
    }

    @Test
    @DisplayName("the two renamed sections keep their long name in llms-full.txt only")
    void renamedSectionsKeepTheirVariantNames() {
        String llms = render(Platform.LLMS);
        String llmsFull = render(Platform.LLMS_FULL);
        for (String[] renamed : RENAMED) {
            String compactHeading = renamed[0];
            String fullHeading = renamed[1];
            String explanation = renamed[2];
            assertTrue(llmsFull.contains("## " + fullHeading + "\n" + explanation),
                "llms-full.txt must carry '" + fullHeading + "' with its explanation");
            assertTrue(llms.contains("## " + compactHeading + "\n"),
                "llms.txt must carry the compact '" + compactHeading + "' heading");
            assertFalse(llms.contains(fullHeading),
                "the long '" + fullHeading + "' name belongs to llms-full.txt only");
            assertFalse(llms.contains(explanation),
                "llms.txt must not carry the '" + compactHeading + "' explanation");
        }
    }

    @Test
    @DisplayName("both files carry exactly one heading per section, so section 45 must join the table")
    void everySectionIsInTheTable() {
        long expected = sections().count() + RENAMED.size();
        assertEquals(expected, headingCount(render(Platform.LLMS)),
            "llms.txt renders a section this table does not pin — add its row");
        assertEquals(expected, headingCount(render(Platform.LLMS_FULL)),
            "llms-full.txt renders a section this table does not pin — add its row");
    }

    private static long headingCount(String rendered) {
        return rendered.lines().filter(line -> line.startsWith("## ")).count();
    }
}
