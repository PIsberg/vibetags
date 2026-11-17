package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.MarkdownSectionMerge;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SourceSetMerge;

/**
 * PlatformRenderer for generating `llms.txt` and `llms-full.txt` discovery formats.
 */
public final class LlmsRenderer implements PlatformRenderer {
    @Override
    public SourceSetMerge sourceSetMerge() {
        return MarkdownSectionMerge::merge;
    }

    /**
     * One section of both files: the model bucket it lists, the formatter for each entry, and its
     * heading in {@code llms.txt} (compact) and {@code llms-full.txt} (full, with an explanatory line).
     *
     * <p>The formatter is looked up when a file is rendered, as it always was, rather than when this
     * class initialises: {@code PlatformDescriptors} constructs the renderers, so touching
     * {@code FormatterRegistry} from this class's static initialiser would add a class-initialisation
     * edge that nothing needs.
     */
    private record Section(Function<GuardrailModel, ? extends Collection<TaggedElement>> elements,
                           Supplier<? extends AnnotationFormatter> formatter,
                           String compactHeading, String fullHeading) {
    }

    /**
     * Every section, in the order both files print them. A section is printed only when something in
     * it renders (#728, #730), so a module with nothing locked gets no locked heading. A compact
     * heading carries its own leading newline except the first; see {@link #appendSection}.
     */
    private static final List<Section> SECTIONS = List.of(
        new Section(GuardrailModel::locked, FormatterRegistry::locked,
            "## Locked Files\n",
            "## Locked Files (Do Not Edit)\nThe following files are locked. AI tools MUST NOT propose modifications to them.\n\n"),
        new Section(GuardrailModel::context, FormatterRegistry::context,
            "\n## Contextual Rules\n",
            "## Contextual Rules\nThese files have specific context and focus areas for AI assistance.\n\n"),
        new Section(GuardrailModel::audit, FormatterRegistry::audit,
            "\n## Security Audit Requirements\n",
            "## Mandatory Security Audit Requirements\nWhen writing or modifying the following files, perform a security audit for the listed vulnerabilities before displaying any code to the user.\n\n"),
        new Section(GuardrailModel::ignore, FormatterRegistry::ignore,
            "\n## Ignored Elements\n",
            "## Ignored Elements\nThe following elements must be completely excluded from AI context. Treat them as non-existent.\n\n"),
        new Section(GuardrailModel::draft, FormatterRegistry::draft,
            "\n## Implementation Tasks\n",
            "## Implementation Tasks\nThe following elements are in draft mode and need implementation.\n\n"),
        new Section(GuardrailModel::privacy, FormatterRegistry::privacy,
            "\n## PII / Privacy Guardrails\n",
            "## PII / Privacy Guardrails\nNever include runtime values of the following elements in logs, console output, external API calls, test fixtures, or mock data.\n\n"),
        new Section(GuardrailModel::core, FormatterRegistry::core,
            "\n## 🧠 Core Functionality\n",
            "## 🧠 Core Functionality\nThe following elements are well-tested core functionality. Make changes with extreme caution.\n\n"),
        new Section(GuardrailModel::performance, FormatterRegistry::performance,
            "\n## ⚡ Performance Constraints\n",
            "## ⚡ Performance Constraints\nThe following elements are on a hot-path and have strict time/space complexity constraints.\n\n"),
        new Section(GuardrailModel::contract, FormatterRegistry::contract,
            "\n## 🔐 Contract-Frozen Signatures\n",
            "## 🔐 Contract-Frozen Signatures\nThe following elements have frozen public API signatures. Internal implementation may be changed, but you MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n"),
        new Section(GuardrailModel::testDriven, FormatterRegistry::testDriven,
            "\n## 🧪 Test-Driven Requirements\n",
            "## 🧪 Test-Driven Requirements\nThe following elements require a matching test update whenever their logic is modified. Changes without tests are incomplete.\n\n"),
        new Section(GuardrailModel::threadSafe, FormatterRegistry::threadSafe,
            "\n## 🧵 Thread-Safe by Design\n",
            "## 🧵 Thread-Safe by Design\nThese elements are explicitly designed to be thread-safe via the named strategy. Preserve the synchronization invariant on every change.\n\n"),
        new Section(GuardrailModel::immutable, FormatterRegistry::immutable,
            "\n## ❄️ Immutable Types\n",
            "## ❄️ Immutable Types\nThe following types are immutable. Never introduce non-final fields, setters, or mutating methods.\n\n"),
        new Section(GuardrailModel::deprecated, FormatterRegistry::deprecated,
            "\n## ⚠️ Deprecated Elements\n",
            "## ⚠️ Deprecated Elements\nThe following elements are deprecated. Suggest migration to the named replacement for any caller and do not extend them.\n\n"),
        new Section(GuardrailModel::observability, FormatterRegistry::observability,
            "\n## 📡 Observability Instrumentation\n",
            "## 📡 Observability Instrumentation\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on.\n\n"),
        new Section(GuardrailModel::regulation, FormatterRegistry::regulation,
            "\n## 📜 Regulatory Compliance\n",
            "## 📜 Regulatory Compliance\nThe following elements implement specific regulatory clauses. Document compliance impact for every change and never weaken the requirement.\n\n"),
        new Section(GuardrailModel::parallelTests, FormatterRegistry::parallelTests,
            "\n## Strict Test Isolation\n",
            "## Strict Test Isolation\nAI tools must enforce strict isolation when generating or modifying tests for these elements.\n\n"),
        new Section(GuardrailModel::legacyBridge, FormatterRegistry::legacyBridge,
            "\n## Legacy Compatibility Bridge\n",
            "## Legacy Compatibility Bridge\nThese elements are legacy or compatibility bridges. Do not restructure or modernize them.\n\n"),
        new Section(GuardrailModel::architecture, FormatterRegistry::architecture,
            "\n## Architectural Boundary Constraints\n",
            "## Architectural Boundary Constraints\nStrict architectural layering must be respected. No illegal references or imports.\n\n"),
        new Section(GuardrailModel::publicApi, FormatterRegistry::publicApi,
            "\n## Public API Surface Protection\n",
            "## Public API Surface Protection\nThese elements expose public API surfaces. Preserve signatures, Javadocs, and backward compatibility.\n\n"),
        new Section(GuardrailModel::strictExceptions, FormatterRegistry::strictExceptions,
            "\n## Strict Exception Handling\n",
            "## Strict Exception Handling\nPrecise and robust exception handling must be enforced. No catching or throwing generic Exception.\n\n"),
        new Section(GuardrailModel::strictTypes, FormatterRegistry::strictTypes,
            "\n## Strict Type Safety\n",
            "## Strict Type Safety\nType safety must be strictly preserved. Loose or erased types are prohibited.\n\n"),
        new Section(GuardrailModel::internationalized, FormatterRegistry::internationalized,
            "\n## Internationalization Mandate\n",
            "## Internationalization Mandate\nUser-facing strings must not be hardcoded; resolve them via localized resources.\n\n"),
        new Section(GuardrailModel::strictClasspath, FormatterRegistry::strictClasspath,
            "\n## Strict Classpath Integrity\n",
            "## Strict Classpath Integrity\nDynamic runtime class loading and reflections are strictly prohibited.\n\n"),
        new Section(GuardrailModel::schemaSafe, FormatterRegistry::schemaSafe,
            "\n## Schema & Serialization Safety\n",
            "## Schema & Serialization Safety\nSchema and serialization compatibility must be strictly preserved.\n\n"),
        new Section(GuardrailModel::idempotent, FormatterRegistry::idempotent,
            "\n## ♻️ Idempotency Guarantees\n",
            "## ♻️ Idempotency Guarantees\nThese operations are idempotent — calling multiple times must produce the same result as calling once.\n\n"),
        new Section(GuardrailModel::featureFlag, FormatterRegistry::featureFlag,
            "\n## 🚩 Feature Flag Gated Code\n",
            "## 🚩 Feature Flag Gated Code\nThese elements are gated behind a feature flag. Preserve the flag check and handle both enabled and disabled code paths.\n\n"),
        new Section(GuardrailModel::secure, FormatterRegistry::secure,
            "\n## 🔐 Security-Critical Code\n",
            "## 🔐 Security-Critical Code\nThese elements are security-critical. Do not weaken security properties. Every change requires security review.\n\n"),
        new Section(GuardrailModel::callersOnly, FormatterRegistry::callersOnly,
            "\n## Access Limitations\n",
            "## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n"),
        new Section(GuardrailModel::sandboxOnly, FormatterRegistry::sandboxOnly,
            "\n## Sandbox & Test Exclusion\n",
            "## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n"),
        new Section(GuardrailModel::memoryBudget, FormatterRegistry::memoryBudget,
            "\n## Memory Allocation Budgets\n",
            "## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n"),
        new Section(GuardrailModel::pure, FormatterRegistry::pure,
            "\n## Deterministic Pure Functions\n",
            "## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations.\n\n"),
        new Section(GuardrailModel::domainModel, FormatterRegistry::domainModel,
            "\n## Framework-Free Domain Entities\n",
            "## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n"),
        new Section(GuardrailModel::extensible, FormatterRegistry::extensible,
            "\n## open-closed Extension Patterns\n",
            "## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n"),
        new Section(GuardrailModel::inputSanitized, FormatterRegistry::inputSanitized,
            "\n## Mandatory Input Sanitization\n",
            "## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n"),
        new Section(GuardrailModel::secureLogging, FormatterRegistry::secureLogging,
            "\n## Secure Logging Masking\n",
            "## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n"),
        new Section(GuardrailModel::explain, FormatterRegistry::explain,
            "\n## Required Chain-of-Thought Explanations\n",
            "## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n"),
        new Section(GuardrailModel::prototype, FormatterRegistry::prototype,
            "\n## Experimental Prototype Stubs\n",
            "## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n"),
        new Section(GuardrailModel::sunset, FormatterRegistry::sunset,
            "\n## Sunset Deprecated APIs\n",
            "## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n"),
        new Section(GuardrailModel::temporary, FormatterRegistry::temporary,
            "\n## Temporary Code Workarounds\n",
            "## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n"),
        new Section(GuardrailModel::generated, FormatterRegistry::generated,
            "\n## Generated Code — Edit The Source\n",
            "## Generated Code — Edit The Source\nThese elements are machine-generated and hand edits are silently overwritten. Read them freely; never write them.\n\n"),
        new Section(GuardrailModel::loadBearing, FormatterRegistry::loadBearing,
            "\n## Load-Bearing Oddities\n",
            "## Load-Bearing Oddities\nThese look wrong, redundant, or over-defensive and are deliberate. Refactoring is allowed only while the stated invariant survives.\n\n"),
        new Section(GuardrailModel::bannedApi, FormatterRegistry::bannedApi,
            "\n## Banned APIs\n",
            "## Banned APIs\nThe following APIs compile at these elements but are prohibited there. Use the sanctioned replacement.\n\n"),
        new Section(GuardrailModel::threadAffinity, FormatterRegistry::threadAffinity,
            "\n## Thread Affinity (Not Thread-Safe)\n",
            "## Thread Affinity (Not Thread-Safe)\nThese elements are safe on exactly one thread. Do not add locks — marshal the call onto the required thread instead.\n\n"),
        new Section(GuardrailModel::keepInSync, FormatterRegistry::keepInSync,
            "\n## Mirrored Elements\n",
            "## Mirrored Elements\nThese are duplicated elsewhere. They may change freely, but a partial change silently desyncs a mirror no compiler checks.\n\n"));

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        boolean full = platform == Platform.LLMS_FULL;
        // llms-full ("the book") renders more per element than this hint assumes; under-sizing
        // only costs a couple of growths, far fewer than starting from a small fixed buffer.
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        appendHeader(sb, context.getProjectName(), full);
        Function<Section, String> heading = full ? Section::fullHeading : Section::compactHeading;
        for (Section section : SECTIONS) {
            appendSection(sb, section.elements().apply(model), platform, heading.apply(section),
                section.formatter().get());
        }
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, String projectName, boolean full) {
        if (full) {
            sb.append("# ").append(projectName).append(" — AI Guardrail Rules\n")
              .append("> Complete AI guardrail configuration generated from source annotations by VibeTags.\n\n")
              .append("This document contains the full set of AI guardrail rules for this project. ")
              .append("AI tools with large context windows (such as Windsurf Cascade, Claude 4.6, or Gemini 1.5 Pro) ")
              .append("may load this file directly instead of fetching individual documentation pages.\n\n");
        } else {
            sb.append("# ").append(projectName).append("\n\n")
              .append("> AI guardrail rules generated from source annotations by VibeTags.\n\n")
              .append("AI tools reading this file should respect the guardrails defined below. ")
              .append("These rules were extracted from Java source annotations at compile time ")
              .append("and apply to all AI assistants including Windsurf Cascade, Cursor, Claude, ")
              .append("GitHub Copilot, and Gemini.\n\n");
        }
    }

    /**
     * Appends {@code heading} and the section's entries, or nothing at all when every entry
     * formats to nothing: a bare {@code @AIAudit} names no checks and renders no entry, and its
     * heading alone read as an audit requirement naming no file (#728).
     *
     * <p>A compact heading carries its own leading newline, except the locked one, which follows
     * the file header's blank line directly. When the locked section is skipped the next compact
     * heading lands on that blank line, so its leading newline is dropped rather than printing two
     * blank lines in a row (#730).
     */
    private static void appendSection(StringBuilder sb, Collection<TaggedElement> elements, Platform platform, String heading, AnnotationFormatter formatter) {
        if (elements.isEmpty()) return;
        int sectionStart = sb.length();
        boolean onBlankLine = sb.length() >= 2 && sb.charAt(sb.length() - 1) == '\n' && sb.charAt(sb.length() - 2) == '\n';
        sb.append(heading, onBlankLine && heading.startsWith("\n") ? 1 : 0, heading.length());
        int bodyStart = sb.length();
        for (TaggedElement e : elements) {
            formatter.format(e, sb, platform);
        }
        if (sb.length() == bodyStart) {
            sb.setLength(sectionStart);
        }
    }
}
