package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating XML-based `CLAUDE.md`.
 */
public final class ClaudeRenderer implements PlatformRenderer {
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

        if (!model.performance().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <performance_constraints>\n");
            for (TaggedElement e : model.performance()) {
                FormatterRegistry.performance().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </performance_constraints>\n");
            sb.append(sec)
                .append("\n<rule>Elements listed in <performance_constraints> are on a hot path. Never introduce O(n²) or worse complexity. Always reason about time and space complexity before suggesting changes.</rule>\n");
        }

        if (!model.contract().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <contract_signatures>\n");
            for (TaggedElement e : model.contract()) {
                FormatterRegistry.contract().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </contract_signatures>\n");
            sb.append(sec)
                .append("\n<rule>You may refactor the internal logic of elements listed in <contract_signatures>, but you MUST NOT change their public signatures: method names, parameter types, parameter order, return types, or checked exceptions.</rule>\n");
        }

        if (!model.testDriven().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <test_driven_requirements>\n");
            ClaudeTestDrivenSection.render(model.testDriven(), sec);
            sec.append("  </test_driven_requirements>\n");
            sb.append(sec)
                .append("\n<rule>For any element listed in <test_driven_requirements>, you MUST provide both the implementation change AND the corresponding test code update in a single response. Changes without tests are incomplete and must not be proposed. Every name under <applies-to> in a <test_driven_default> block inherits that block's coverage goal, frameworks, and test-location convention (with {path} standing for the element's package path).</rule>\n");
        }

        if (!model.threadSafe().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <thread_safe_elements>\n");
            for (TaggedElement e : model.threadSafe()) {
                FormatterRegistry.threadSafe().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </thread_safe_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements listed in <thread_safe_elements> are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning in the change description.</rule>\n");
        }

        if (!model.immutable().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <immutable_types>\n");
            for (TaggedElement e : model.immutable()) {
                FormatterRegistry.immutable().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </immutable_types>\n");
            sb.append(sec)
                .append("\n<rule>Types listed in <immutable_types> are immutable by design. Never introduce non-final fields, setters, or methods that mutate instance state.</rule>\n");
        }

        if (!model.deprecated().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <deprecated_elements>\n");
            for (TaggedElement e : model.deprecated()) {
                FormatterRegistry.deprecated().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </deprecated_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements listed in <deprecated_elements> are scheduled for removal. Do not extend them. When working with code that calls them, suggest migrating to the listed replacement.</rule>\n");
        }

        if (!model.observability().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <observability_instrumentation>\n");
            for (TaggedElement e : model.observability()) {
                FormatterRegistry.observability().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </observability_instrumentation>\n");
            sb.append(sec)
                .append("\n<rule>Elements listed in <observability_instrumentation> publish metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the corresponding dashboard update.</rule>\n");
        }

        if (!model.regulation().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <regulatory_elements>\n");
            for (TaggedElement e : model.regulation()) {
                FormatterRegistry.regulation().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </regulatory_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements listed in <regulatory_elements> implement specific regulatory clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.</rule>\n");
        }

        if (!model.parallelTests().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <test_isolation_elements>\n");
            for (TaggedElement e : model.parallelTests()) {
                FormatterRegistry.parallelTests().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </test_isolation_elements>\n");
            sb.append(sec)
                .append("\n<rule>For elements in <test_isolation_elements>, all generated or modified tests MUST run in complete isolation (no shared state, external resource conflicts, or order dependencies).</rule>\n");
        }

        if (!model.legacyBridge().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <legacy_bridge_elements>\n");
            for (TaggedElement e : model.legacyBridge()) {
                FormatterRegistry.legacyBridge().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </legacy_bridge_elements>\n");
            sb.append(sec)
                .append("\n<rule>Do not modernise, elegant-ize, or refactor structural patterns of elements in <legacy_bridge_elements>. Only modify internal business logic as explicitly requested.</rule>\n");
        }

        if (!model.architecture().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <architecture_elements>\n");
            for (TaggedElement e : model.architecture()) {
                FormatterRegistry.architecture().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </architecture_elements>\n");
            sb.append(sec)
                .append("\n<rule>Respect layered architectural constraints in <architecture_elements>. Boundary crossing references are strictly prohibited.</rule>\n");
        }

        if (!model.publicApi().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <public_api_elements>\n");
            for (TaggedElement e : model.publicApi()) {
                FormatterRegistry.publicApi().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </public_api_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <public_api_elements> expose public API. Preserve public signature, Javadoc, and backwards compatibility without exceptions.</rule>\n");
        }

        if (!model.strictExceptions().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <strict_exceptions_elements>\n");
            for (TaggedElement e : model.strictExceptions()) {
                FormatterRegistry.strictExceptions().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </strict_exceptions_elements>\n");
            sb.append(sec)
                .append("\n<rule>Catching or throwing generic Exception/Throwable is strictly prohibited in <strict_exceptions_elements>. Precise or custom exceptions required.</rule>\n");
        }

        if (!model.strictTypes().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <strict_types_elements>\n");
            for (TaggedElement e : model.strictTypes()) {
                FormatterRegistry.strictTypes().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </strict_types_elements>\n");
            sb.append(sec)
                .append("\n<rule>Loose typing (Object, Map<String, Object>, raw types) is strictly prohibited in <strict_types_elements>. Enforce type safety.</rule>\n");
        }

        if (!model.internationalized().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <internationalized_elements>\n");
            for (TaggedElement e : model.internationalized()) {
                FormatterRegistry.internationalized().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </internationalized_elements>\n");
            sb.append(sec)
                .append("\n<rule>Do not hardcode user-facing strings in <internationalized_elements>. Resolve all text via localization resource/message bundles.</rule>\n");
        }

        if (!model.strictClasspath().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <strict_classpath_elements>\n");
            for (TaggedElement e : model.strictClasspath()) {
                FormatterRegistry.strictClasspath().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </strict_classpath_elements>\n");
            sb.append(sec)
                .append("\n<rule>Dynamic class loading, custom classloaders, reflection hacks, or unverified external code are prohibited in <strict_classpath_elements>.</rule>\n");
        }

        if (!model.schemaSafe().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <schema_safe_elements>\n");
            for (TaggedElement e : model.schemaSafe()) {
                FormatterRegistry.schemaSafe().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </schema_safe_elements>\n");
            sb.append(sec)
                .append("\n<rule>Database or contract schema / serialization safety must be preserved in <schema_safe_elements>. Do not alter structures without migration paths.</rule>\n");
        }

        if (!model.idempotent().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <idempotent_elements>\n");
            for (TaggedElement e : model.idempotent()) {
                FormatterRegistry.idempotent().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </idempotent_elements>\n");
            sb.append(sec)
                .append("\n<rule>Operations listed in <idempotent_elements> must remain idempotent. Never introduce side effects that cause repeated invocations to produce different results.</rule>\n");
        }

        if (!model.featureFlag().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <feature_flag_elements>\n");
            for (TaggedElement e : model.featureFlag()) {
                FormatterRegistry.featureFlag().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </feature_flag_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements listed in <feature_flag_elements> are gated by a feature flag. Always preserve the flag check — never assume the flag is always active.</rule>\n");
        }

        appendSecureSection(sb, model);

        // New annotations formatting sections for Claude
        if (!model.callersOnly().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <access_limitations>\n");
            for (TaggedElement e : model.callersOnly()) {
                FormatterRegistry.callersOnly().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </access_limitations>\n");
            sb.append(sec)
                .append("\n<rule>Do not invoke elements in <access_limitations> from outside their specified allowed caller packages or classes.</rule>\n");
        }

        if (!model.sandboxOnly().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <sandbox_only_elements>\n");
            for (TaggedElement e : model.sandboxOnly()) {
                FormatterRegistry.sandboxOnly().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </sandbox_only_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <sandbox_only_elements> belong exclusively to sandbox or test environments. Never import or invoke them in production code paths.</rule>\n");
        }

        if (!model.memoryBudget().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <memory_budget_elements>\n");
            for (TaggedElement e : model.memoryBudget()) {
                FormatterRegistry.memoryBudget().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </memory_budget_elements>\n");
            sb.append(sec)
                .append("\n<rule>Avoid runtime heap object allocations, autoboxing, or dynamic overhead within classes/methods in <memory_budget_elements>.</rule>\n");
        }

        if (!model.pure().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <pure_functions>\n");
            for (TaggedElement e : model.pure()) {
                FormatterRegistry.pure().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </pure_functions>\n");
            sb.append(sec)
                .append("\n<rule>Methods in <pure_functions> must remain mathematically pure. Side effects, mutations of class/static state, or blocking operations are strictly forbidden.</rule>\n");
        }

        if (!model.domainModel().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <domain_model_elements>\n");
            for (TaggedElement e : model.domainModel()) {
                FormatterRegistry.domainModel().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </domain_model_elements>\n");
            sb.append(sec)
                .append("\n<rule>Classes in <domain_model_elements> are pure domain models. Do not import or reference database or web framework dependencies (Spring, Hibernate, JPA, Jackson).</rule>\n");
        }

        if (!model.extensible().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <extensible_patterns>\n");
            for (TaggedElement e : model.extensible()) {
                FormatterRegistry.extensible().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </extensible_patterns>\n");
            sb.append(sec)
                .append("\n<rule>Respect extensibility guidelines for elements in <extensible_patterns>. Implement strategy/visitor extensions rather than expanding branch conditional logic.</rule>\n");
        }

        if (!model.inputSanitized().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <sanitization_elements>\n");
            for (TaggedElement e : model.inputSanitized()) {
                FormatterRegistry.inputSanitized().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </sanitization_elements>\n");
            sb.append(sec)
                .append("\n<rule>Strict input sanitization is mandatory for elements in <sanitization_elements>. Raw input must pass through approved filters before hitting queries or renderers.</rule>\n");
        }

        if (!model.secureLogging().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <secure_logging_elements>\n");
            for (TaggedElement e : model.secureLogging()) {
                FormatterRegistry.secureLogging().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </secure_logging_elements>\n");
            sb.append(sec)
                .append("\n<rule>Sensitive variables in <secure_logging_elements> must never be printed or logged in raw form. Enforce secure masking or hashing.</rule>\n");
        }

        if (!model.explain().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <explain_elements>\n");
            for (TaggedElement e : model.explain()) {
                FormatterRegistry.explain().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </explain_elements>\n");
            sb.append(sec)
                .append("\n<rule>Any modification to elements in <explain_elements> requires an explicit, structured Chain-of-Thought markdown description of changes and complexity analysis.</rule>\n");
        }

        if (!model.prototype().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <prototype_elements>\n");
            for (TaggedElement e : model.prototype()) {
                FormatterRegistry.prototype().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </prototype_elements>\n");
            sb.append(sec)
                .append("\n<rule>Classes in <prototype_elements> are experimental prototypes. Strict rules are relaxed locally, but production classes must never import or depend on them.</rule>\n");
        }

        if (!model.sunset().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <sunset_elements>\n");
            for (TaggedElement e : model.sunset()) {
                FormatterRegistry.sunset().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </sunset_elements>\n");
            sb.append(sec)
                .append("\n<rule>Do not introduce *new* references or calls to sunset elements in <sunset_elements>. Migrate callers to their modern replacements.</rule>\n");
        }

        if (!model.temporary().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <temporary_workarounds>\n");
            for (TaggedElement e : model.temporary()) {
                FormatterRegistry.temporary().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </temporary_workarounds>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <temporary_workarounds> are short-lived stubs or hotfixes that must be refactored or deleted before their designated expiration date.</rule>\n");
        }

        if (!model.generated().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <generated_elements>\n");
            for (TaggedElement e : model.generated()) {
                FormatterRegistry.generated().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </generated_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <generated_elements> are machine-generated: read them to understand behavior, but never edit them — changes are silently overwritten. Apply the change to the named source and regenerate instead.</rule>\n");
        }

        if (!model.loadBearing().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <load_bearing_elements>\n");
            for (TaggedElement e : model.loadBearing()) {
                FormatterRegistry.loadBearing().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </load_bearing_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <load_bearing_elements> look redundant or over-defensive but are deliberate. You may refactor them, but the stated invariant must survive; do not \"clean up\" the oddity itself.</rule>\n");
        }

        if (!model.bannedApi().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <banned_apis>\n");
            for (TaggedElement e : model.bannedApi()) {
                FormatterRegistry.bannedApi().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </banned_apis>\n");
            sb.append(sec)
                .append("\n<rule>The APIs listed in <banned_apis> compile at those elements but are prohibited there. Use the stated replacement; if none is given, ask rather than substituting an equivalent.</rule>\n");
        }

        if (!model.threadAffinity().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <thread_affinity_elements>\n");
            for (TaggedElement e : model.threadAffinity()) {
                FormatterRegistry.threadAffinity().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </thread_affinity_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <thread_affinity_elements> are safe on exactly one thread — the opposite of thread-safe. Never add locks or synchronization to \"make them safe\"; marshal the call onto the required thread instead.</rule>\n");
        }

        if (!model.keepInSync().isEmpty()) {
            StringBuilder sec = new StringBuilder("  <mirrored_elements>\n");
            for (TaggedElement e : model.keepInSync()) {
                FormatterRegistry.keepInSync().format(e, sec, Platform.CLAUDE);
            }
            sec.append("  </mirrored_elements>\n");
            sb.append(sec)
                .append("\n<rule>Elements in <mirrored_elements> are duplicated at the listed sites. They may change freely, but every mirror must change in the same commit — a partial edit desyncs silently and no compiler will catch it.</rule>\n");
        }

        sb.append("</project_guardrails>\n")
            .append("\n<rule>Never propose edits to files listed in <locked_files>.</rule>\n");

        return sb.toString();
    }

    // --- Always-inline safety sections, shared by the full render() and the scoped-index variant.
    //     Extracted so the two code paths cannot drift; render() emits identical bytes to before. ---

    private static void appendAuditSection(StringBuilder sb, GuardrailModel model) {
        if (model.audit().isEmpty()) {
            return;
        }
        StringBuilder sec = new StringBuilder("  <audit_requirements>\n");
        for (TaggedElement e : model.audit()) {
            FormatterRegistry.audit().format(e, sec, Platform.CLAUDE);
        }
        sec.append("  </audit_requirements>\n");
        sb.append('\n').append(sec)
            .append("\n<rule>\n  If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed <vulnerability_check> items. If your code introduces these vulnerabilities, you must rewrite it before displaying it to the user.\n</rule>\n");
    }

    private static void appendIgnoreSection(StringBuilder sb, GuardrailModel model) {
        if (model.ignore().isEmpty()) {
            return;
        }
        StringBuilder sec = new StringBuilder("  <ignored_elements>\n");
        for (TaggedElement e : model.ignore()) {
            FormatterRegistry.ignore().format(e, sec, Platform.CLAUDE);
        }
        sec.append("  </ignored_elements>\n");
        sb.append(sec)
            .append("\n<rule>Never reference or suggest changes to any element listed in <ignored_elements>. Treat these as if they do not exist.</rule>\n");
    }

    private static void appendPrivacySection(StringBuilder sb, GuardrailModel model) {
        if (model.privacy().isEmpty()) {
            return;
        }
        StringBuilder sec = new StringBuilder("  <pii_guardrails>\n");
        for (TaggedElement e : model.privacy()) {
            FormatterRegistry.privacy().format(e, sec, Platform.CLAUDE);
        }
        sec.append("  </pii_guardrails>\n");
        sb.append(sec)
            .append("\n<rule>\n  Never include runtime values of elements listed in <pii_guardrails> in logs, console output, external API calls, test fixtures, mock data, or code suggestions. Treat their values as strictly confidential.\n</rule>\n");
    }

    private static void appendCoreSection(StringBuilder sb, GuardrailModel model) {
        if (model.core().isEmpty()) {
            return;
        }
        StringBuilder sec = new StringBuilder("  <core_elements>\n");
        for (TaggedElement e : model.core()) {
            FormatterRegistry.core().format(e, sec, Platform.CLAUDE);
        }
        sec.append("  </core_elements>\n");
        sb.append(sec)
            .append("\n<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>\n");
    }

    private static void appendSecureSection(StringBuilder sb, GuardrailModel model) {
        if (model.secure().isEmpty()) {
            return;
        }
        StringBuilder sec = new StringBuilder("  <security_elements>\n");
        for (TaggedElement e : model.secure()) {
            FormatterRegistry.secure().format(e, sec, Platform.CLAUDE);
        }
        sec.append("  </security_elements>\n");
        sb.append(sec)
            .append("\n<rule>Elements listed in <security_elements> are security-critical. Never weaken their security properties. Every proposed change must be explicitly reviewed for security impact.</rule>\n");
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
