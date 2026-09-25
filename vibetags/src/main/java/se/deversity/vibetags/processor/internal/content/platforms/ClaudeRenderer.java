package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.ClaudeSectionMerge;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SourceSetMerge;

import java.util.Collection;

/**
 * PlatformRenderer for generating XML-based `CLAUDE.md`.
 */
public final class ClaudeRenderer implements PlatformRenderer {
    @Override
    public SourceSetMerge sourceSetMerge() {
        return ClaudeSectionMerge::merge;
    }

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        if (GranularIndexSection.indexActive(platform, context)) {
            // Granular sibling (.claude/rules/) opted in: collapse the per-element buckets to a
            // scoped-rules index, keeping only the always-loaded safety guardrails inline.
            return renderIndexed(model, platform, context);
        }
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("<!-- ").append(context.getGeneratedHeader().trim()).append(" -->\n<project_guardrails>\n  <locked_files>\n");

        for (TaggedElement e : model.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.CLAUDE);
        }

        sb.append("  </locked_files>\n  <contextual_instructions>\n");
        for (TaggedElement e : model.context()) {
            FormatterRegistry.context().format(e, sb, Platform.CLAUDE);
        }
        sb.append("  </contextual_instructions>\n");

        appendAuditSection(sb, model);

        appendIgnoreSection(sb, model);

        if (!model.draft().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <implementation_tasks>\n");
            for (TaggedElement e : model.draft()) {
                FormatterRegistry.draft().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </implementation_tasks>\n");
            sb.append(sec);
        }

        appendPrivacySection(sb, model);

        appendCoreSection(sb, model);

        appendSection(sb, model.performance(), FormatterRegistry.performance(), "performance_constraints",
            "\n<rule>Elements listed in <performance_constraints> are on a hot path. Never introduce O(n²) or worse complexity. Always reason about time and space complexity before suggesting changes.</rule>\n");

        appendSection(sb, model.contract(), FormatterRegistry.contract(), "contract_signatures",
            "\n<rule>You may refactor the internal logic of elements listed in <contract_signatures>, but you MUST NOT change their public signatures: method names, parameter types, parameter order, return types, or checked exceptions.</rule>\n");

        if (!model.testDriven().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <test_driven_requirements>\n");
            ClaudeTestDrivenSection.render(model.testDriven(), sec);
            sec.append("  </test_driven_requirements>\n");
            sb.append(sec)
                .append("\n<rule>For any element listed in <test_driven_requirements>, you MUST provide both the implementation change AND the corresponding test code update in a single response. Changes without tests are incomplete and must not be proposed. Every name under <applies-to> in a <test_driven_default> block inherits that block's coverage goal, frameworks, and test-location convention (with {path} standing for the element's package path).</rule>\n");
        }

        appendSection(sb, model.threadSafe(), FormatterRegistry.threadSafe(), "thread_safe_elements",
            "\n<rule>Elements listed in <thread_safe_elements> are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning in the change description.</rule>\n");

        appendSection(sb, model.immutable(), FormatterRegistry.immutable(), "immutable_types",
            "\n<rule>Types listed in <immutable_types> are immutable by design. Never introduce non-final fields, setters, or methods that mutate instance state.</rule>\n");

        appendSection(sb, model.deprecated(), FormatterRegistry.deprecated(), "deprecated_elements",
            "\n<rule>Elements listed in <deprecated_elements> are scheduled for removal. Do not extend them. When working with code that calls them, suggest migrating to the listed replacement.</rule>\n");

        appendSection(sb, model.observability(), FormatterRegistry.observability(), "observability_instrumentation",
            "\n<rule>Elements listed in <observability_instrumentation> publish metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the corresponding dashboard update.</rule>\n");

        appendSection(sb, model.regulation(), FormatterRegistry.regulation(), "regulatory_elements",
            "\n<rule>Elements listed in <regulatory_elements> implement specific regulatory clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.</rule>\n");

        appendSection(sb, model.parallelTests(), FormatterRegistry.parallelTests(), "test_isolation_elements",
            "\n<rule>For elements in <test_isolation_elements>, all generated or modified tests MUST run in complete isolation (no shared state, external resource conflicts, or order dependencies).</rule>\n");

        appendSection(sb, model.legacyBridge(), FormatterRegistry.legacyBridge(), "legacy_bridge_elements",
            "\n<rule>Do not modernise, elegant-ize, or refactor structural patterns of elements in <legacy_bridge_elements>. Only modify internal business logic as explicitly requested.</rule>\n");

        appendSection(sb, model.architecture(), FormatterRegistry.architecture(), "architecture_elements",
            "\n<rule>Respect layered architectural constraints in <architecture_elements>. Boundary crossing references are strictly prohibited.</rule>\n");

        appendSection(sb, model.publicApi(), FormatterRegistry.publicApi(), "public_api_elements",
            "\n<rule>Elements in <public_api_elements> expose public API. Preserve public signature, Javadoc, and backwards compatibility without exceptions.</rule>\n");

        appendSection(sb, model.strictExceptions(), FormatterRegistry.strictExceptions(), "strict_exceptions_elements",
            "\n<rule>Catching or throwing generic Exception/Throwable is strictly prohibited in <strict_exceptions_elements>. Precise or custom exceptions required.</rule>\n");

        appendSection(sb, model.strictTypes(), FormatterRegistry.strictTypes(), "strict_types_elements",
            "\n<rule>Loose typing (Object, Map<String, Object>, raw types) is strictly prohibited in <strict_types_elements>. Enforce type safety.</rule>\n");

        appendSection(sb, model.internationalized(), FormatterRegistry.internationalized(), "internationalized_elements",
            "\n<rule>Do not hardcode user-facing strings in <internationalized_elements>. Resolve all text via localization resource/message bundles.</rule>\n");

        appendSection(sb, model.strictClasspath(), FormatterRegistry.strictClasspath(), "strict_classpath_elements",
            "\n<rule>Dynamic class loading, custom classloaders, reflection hacks, or unverified external code are prohibited in <strict_classpath_elements>.</rule>\n");

        appendSection(sb, model.schemaSafe(), FormatterRegistry.schemaSafe(), "schema_safe_elements",
            "\n<rule>Database or contract schema / serialization safety must be preserved in <schema_safe_elements>. Do not alter structures without migration paths.</rule>\n");

        appendSection(sb, model.idempotent(), FormatterRegistry.idempotent(), "idempotent_elements",
            "\n<rule>Operations listed in <idempotent_elements> must remain idempotent. Never introduce side effects that cause repeated invocations to produce different results.</rule>\n");

        appendSection(sb, model.featureFlag(), FormatterRegistry.featureFlag(), "feature_flag_elements",
            "\n<rule>Elements listed in <feature_flag_elements> are gated by a feature flag. Always preserve the flag check — never assume the flag is always active.</rule>\n");

        appendSecureSection(sb, model);

        // New annotations formatting sections for Claude
        appendSection(sb, model.callersOnly(), FormatterRegistry.callersOnly(), "access_limitations",
            "\n<rule>Do not invoke elements in <access_limitations> from outside their specified allowed caller packages or classes.</rule>\n");

        appendSection(sb, model.sandboxOnly(), FormatterRegistry.sandboxOnly(), "sandbox_only_elements",
            "\n<rule>Elements in <sandbox_only_elements> belong exclusively to sandbox or test environments. Never import or invoke them in production code paths.</rule>\n");

        appendSection(sb, model.memoryBudget(), FormatterRegistry.memoryBudget(), "memory_budget_elements",
            "\n<rule>Avoid runtime heap object allocations, autoboxing, or dynamic overhead within classes/methods in <memory_budget_elements>.</rule>\n");

        appendSection(sb, model.pure(), FormatterRegistry.pure(), "pure_functions",
            "\n<rule>Methods in <pure_functions> must remain mathematically pure. Side effects, mutations of class/static state, or blocking operations are strictly forbidden.</rule>\n");

        appendSection(sb, model.domainModel(), FormatterRegistry.domainModel(), "domain_model_elements",
            "\n<rule>Classes in <domain_model_elements> are pure domain models. Do not import or reference database or web framework dependencies (Spring, Hibernate, JPA, Jackson).</rule>\n");

        appendSection(sb, model.extensible(), FormatterRegistry.extensible(), "extensible_patterns",
            "\n<rule>Respect extensibility guidelines for elements in <extensible_patterns>. Implement strategy/visitor extensions rather than expanding branch conditional logic.</rule>\n");

        appendSection(sb, model.inputSanitized(), FormatterRegistry.inputSanitized(), "sanitization_elements",
            "\n<rule>Strict input sanitization is mandatory for elements in <sanitization_elements>. Raw input must pass through approved filters before hitting queries or renderers.</rule>\n");

        appendSection(sb, model.secureLogging(), FormatterRegistry.secureLogging(), "secure_logging_elements",
            "\n<rule>Sensitive variables in <secure_logging_elements> must never be printed or logged in raw form. Enforce secure masking or hashing.</rule>\n");

        appendSection(sb, model.explain(), FormatterRegistry.explain(), "explain_elements",
            "\n<rule>Any modification to elements in <explain_elements> requires an explicit, structured Chain-of-Thought markdown description of changes and complexity analysis.</rule>\n");

        appendSection(sb, model.prototype(), FormatterRegistry.prototype(), "prototype_elements",
            "\n<rule>Classes in <prototype_elements> are experimental prototypes. Strict rules are relaxed locally, but production classes must never import or depend on them.</rule>\n");

        appendSection(sb, model.sunset(), FormatterRegistry.sunset(), "sunset_elements",
            "\n<rule>Do not introduce *new* references or calls to sunset elements in <sunset_elements>. Migrate callers to their modern replacements.</rule>\n");

        appendSection(sb, model.temporary(), FormatterRegistry.temporary(), "temporary_workarounds",
            "\n<rule>Elements in <temporary_workarounds> are short-lived stubs or hotfixes that must be refactored or deleted before their designated expiration date.</rule>\n");

        appendSection(sb, model.generated(), FormatterRegistry.generated(), "generated_elements",
            "\n<rule>Elements in <generated_elements> are machine-generated: read them to understand behavior, but never edit them — changes are silently overwritten. Apply the change to the named source and regenerate instead.</rule>\n");

        appendSection(sb, model.loadBearing(), FormatterRegistry.loadBearing(), "load_bearing_elements",
            "\n<rule>Elements in <load_bearing_elements> look redundant or over-defensive but are deliberate. You may refactor them, but the stated invariant must survive; do not \"clean up\" the oddity itself.</rule>\n");

        appendSection(sb, model.bannedApi(), FormatterRegistry.bannedApi(), "banned_apis",
            "\n<rule>The APIs listed in <banned_apis> compile at those elements but are prohibited there. Use the stated replacement; if none is given, ask rather than substituting an equivalent.</rule>\n");

        appendSection(sb, model.threadAffinity(), FormatterRegistry.threadAffinity(), "thread_affinity_elements",
            "\n<rule>Elements in <thread_affinity_elements> are safe on exactly one thread — the opposite of thread-safe. Never add locks or synchronization to \"make them safe\"; marshal the call onto the required thread instead.</rule>\n");

        appendSection(sb, model.keepInSync(), FormatterRegistry.keepInSync(), "mirrored_elements",
            "\n<rule>Elements in <mirrored_elements> are duplicated at the listed sites. They may change freely, but every mirror must change in the same commit — a partial edit desyncs silently and no compiler will catch it.</rule>\n");

        sb.append("</project_guardrails>\n")
            .append("\n<rule>Never propose edits to files listed in <locked_files>.</rule>\n");

        return sb.toString();
    }

    /**
     * One {@code <tag>} section followed by its {@code <rule>}, or nothing when the bucket is empty.
     *
     * <p>Unlike {@link AnnotationSections#render}, this does not roll the wrapper back when every
     * element formats to nothing: CLAUDE.md has always emitted the empty element plus its rule, and
     * the golden files pin that.
     */
    private static void appendSection(StringBuilder sb, Collection<TaggedElement> elements,
                                      AnnotationFormatter formatter, String tag, String rule) {
        if (elements.isEmpty()) {
            return;
        }
        sb.append("  <").append(tag).append(">\n");
        for (TaggedElement e : elements) {
            formatter.format(e, sb, Platform.CLAUDE);
        }
        sb.append("  </").append(tag).append(">\n").append(rule);
    }

    // --- Always-inline safety sections, shared by the full render() and the scoped-index variant.
    //     Extracted so the two code paths cannot drift; render() emits identical bytes to before. ---

    private static void appendAuditSection(StringBuilder sb, GuardrailModel model) {
        if (model.audit().isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (TaggedElement e : model.audit()) {
            FormatterRegistry.audit().format(e, body, Platform.CLAUDE);
        }
        // A bare @AIAudit carries no checkFor() list, so the formatter contributes nothing. Emitting
        // the wrapper anyway leaves an empty <audit_requirements> and a <rule> pointing at it — the
        // agent is told to consult a list that is not there.
        if (body.isEmpty()) {
            return;
        }
        StringBuilder sec = new StringBuilder("  <audit_requirements>\n");
        sec.append(body).append("  </audit_requirements>\n");
        sb.append('\n').append(sec)
            .append("\n<rule>\n  If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed <vulnerability_check> items. If your code introduces these vulnerabilities, you must rewrite it before displaying it to the user.\n</rule>\n");
    }

    private static void appendIgnoreSection(StringBuilder sb, GuardrailModel model) {
        appendSection(sb, model.ignore(), FormatterRegistry.ignore(), "ignored_elements",
            "\n<rule>Never reference or suggest changes to any element listed in <ignored_elements>. Treat these as if they do not exist.</rule>\n");
    }

    private static void appendPrivacySection(StringBuilder sb, GuardrailModel model) {
        appendSection(sb, model.privacy(), FormatterRegistry.privacy(), "pii_guardrails",
            "\n<rule>\n  Never include runtime values of elements listed in <pii_guardrails> in logs, console output, external API calls, test fixtures, mock data, or code suggestions. Treat their values as strictly confidential.\n</rule>\n");
    }

    private static void appendCoreSection(StringBuilder sb, GuardrailModel model) {
        appendSection(sb, model.core(), FormatterRegistry.core(), "core_elements",
            "\n<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>\n");
    }

    private static void appendSecureSection(StringBuilder sb, GuardrailModel model) {
        appendSection(sb, model.secure(), FormatterRegistry.secure(), "security_elements",
            "\n<rule>Elements listed in <security_elements> are security-critical. Never weaken their security properties. Every proposed change must be explicitly reviewed for security impact.</rule>\n");
    }

    /**
     * Scoped-index variant of {@link #render}: emitted when Claude's granular sibling
     * ({@code .claude/rules/}) is also opted in. Keeps only the always-loaded safety guardrails
     * inline (locked, audit, ignore, privacy, core, secure) and points at the scoped rule files for
     * every other element via {@link GranularIndexSection#appendXmlIndex}. The scoped files carry
     * the full per-element detail, so nothing is lost — it just stops being duplicated in the
     * always-on file.
     */
    private static String renderIndexed(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("<!-- ").append(context.getGeneratedHeader().trim()).append(" -->\n<project_guardrails>\n");
        // An empty <locked_files/> plus its trailing rule costs three lines. The reactor merge emits
        // this block once per module, so for a project where most modules lock nothing it is the
        // bulk of a file whose whole purpose is to be lean (issue #319). Full (non-indexed) output
        // still emits the element unconditionally, so single-opt-in aggregates are unchanged.
        boolean anyLocked = !model.locked().isEmpty();
        if (anyLocked) {
            sb.append("  <locked_files>\n");
            for (TaggedElement e : model.locked()) {
                FormatterRegistry.locked().format(e, sb, Platform.CLAUDE);
            }
            sb.append("  </locked_files>\n");
        }
        appendAuditSection(sb, model);
        appendIgnoreSection(sb, model);
        appendPrivacySection(sb, model);
        appendCoreSection(sb, model);
        appendSecureSection(sb, model);
        GranularIndexSection.appendXmlIndex(sb, platform, context);
        sb.append("</project_guardrails>\n");
        if (anyLocked) {
            sb.append("\n<rule>Never propose edits to files listed in <locked_files>.</rule>\n");
        }
        return sb.toString();
    }
}
