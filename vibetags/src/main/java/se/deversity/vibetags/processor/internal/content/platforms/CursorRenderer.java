package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `.cursorrules`.
 */
public final class CursorRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# AUTO-GENERATED AI RULES\n").append(context.getGeneratedHeader()).append("# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.CURSOR);
        }

        sb.append("\n## CONTEXTUAL RULES\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.CURSOR);
        }

        if (!collector.audit().isEmpty()) {
            sb.append("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\n\n");
            for (Element e : collector.audit()) {
                FormatterRegistry.audit().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.ignore().isEmpty()) {
            sb.append("\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n");
            for (Element e : collector.ignore()) {
                FormatterRegistry.ignore().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.draft().isEmpty()) {
            sb.append("\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n");
            for (Element e : collector.draft()) {
                FormatterRegistry.draft().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.privacy().isEmpty()) {
            sb.append("\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNEVER include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n");
            for (Element e : collector.privacy()) {
                FormatterRegistry.privacy().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.core().isEmpty()) {
            sb.append("\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
            for (Element e : collector.core()) {
                FormatterRegistry.core().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.performance().isEmpty()) {
            sb.append("\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nThe following elements are on a hot path. Never introduce O(n²) complexity. Always reason about time/space before proposing changes.\n\n");
            for (Element e : collector.performance()) {
                FormatterRegistry.performance().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.contract().isEmpty()) {
            sb.append("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nThe following elements have contract-frozen public signatures. You MAY change internal implementation logic, but MUST NOT modify method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
            for (Element e : collector.contract()) {
                FormatterRegistry.contract().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.testDriven().isEmpty()) {
            sb.append("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nThe following elements require a corresponding test update whenever their logic is modified.\nAI MUST NOT propose changes to these elements without also providing the matching test code.\n\n");
            for (Element e : collector.testDriven()) {
                FormatterRegistry.testDriven().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.threadSafe().isEmpty()) {
            sb.append("\n## 🧵 THREAD-SAFE BY DESIGN\nThe following elements are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning.\n\n");
            for (Element e : collector.threadSafe()) {
                FormatterRegistry.threadSafe().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.immutable().isEmpty()) {
            sb.append("\n## ❄️ IMMUTABLE TYPES\nThe following types are declared immutable. NEVER introduce non-final fields, setters, or mutating methods.\n\n");
            for (Element e : collector.immutable()) {
                FormatterRegistry.immutable().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.deprecated().isEmpty()) {
            sb.append("\n## ⚠️ DEPRECATED — ROUTE CALLERS AWAY\nThe following elements are deprecated. Do not extend them. Suggest migrating any caller to the named replacement.\n\n");
            for (Element e : collector.deprecated()) {
                FormatterRegistry.deprecated().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.observability().isEmpty()) {
            sb.append("\n## 📡 OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the affected dashboard.\n\n");
            for (Element e : collector.observability()) {
                FormatterRegistry.observability().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.regulation().isEmpty()) {
            sb.append("\n## 📜 REGULATORY COMPLIANCE\nThe following elements implement specific compliance clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.\n\n");
            for (Element e : collector.regulation()) {
                FormatterRegistry.regulation().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.parallelTests().isEmpty()) {
            sb.append("\n## 🧪 STRICT TEST ISOLATION\nThe following elements must be strictly isolated when generating or modifying tests. No shared mutable state or resource conflicts are permitted.\n\n");
            for (Element e : collector.parallelTests()) {
                FormatterRegistry.parallelTests().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.legacyBridge().isEmpty()) {
            sb.append("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nThe following elements are legacy compatibility bridges. Do not attempt to modernize or refactor their structural patterns; only modify internal business logic as explicitly requested.\n\n");
            for (Element e : collector.legacyBridge()) {
                FormatterRegistry.legacyBridge().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.architecture().isEmpty()) {
            sb.append("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nThe following elements have strict layering constraints. Prohibit imports or references that cross boundaries.\n\n");
            for (Element e : collector.architecture()) {
                FormatterRegistry.architecture().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.publicApi().isEmpty()) {
            sb.append("\n## 🔌 PUBLIC API SURFACE PROTECTION\nThe following elements are public-facing API surfaces. Always preserve public signatures, Javadoc, and backwards compatibility.\n\n");
            for (Element e : collector.publicApi()) {
                FormatterRegistry.publicApi().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.strictExceptions().isEmpty()) {
            sb.append("\n## 🚨 STRICT EXCEPTION HANDLING\nThe following elements have strict exception constraints. Prohibit catching or throwing generic Exception/Throwable.\n\n");
            for (Element e : collector.strictExceptions()) {
                FormatterRegistry.strictExceptions().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.strictTypes().isEmpty()) {
            sb.append("\n## 🏷️ STRICT TYPE SAFETY\nThe following elements prohibit loose typing such as Object or Map<String, Object>. Strong type safety is required.\n\n");
            for (Element e : collector.strictTypes()) {
                FormatterRegistry.strictTypes().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.internationalized().isEmpty()) {
            sb.append("\n## 🌐 INTERNATIONALIZATION MANDATE\nThe following elements implement i18n requirements. Prohibit hardcoded user-facing strings.\n\n");
            for (Element e : collector.internationalized()) {
                FormatterRegistry.internationalized().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.strictClasspath().isEmpty()) {
            sb.append("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nThe following elements prohibit dynamic runtime class loading, reflections, or loading of unverified dynamic code.\n\n");
            for (Element e : collector.strictClasspath()) {
                FormatterRegistry.strictClasspath().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.schemaSafe().isEmpty()) {
            sb.append("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nThe following elements have schema safety constraints. Restrict changing formats/fields without a backward-compatible migration plan.\n\n");
            for (Element e : collector.schemaSafe()) {
                FormatterRegistry.schemaSafe().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.idempotent().isEmpty()) {
            sb.append("\n## ♻️ IDEMPOTENCY GUARANTEES\nThe following operations are idempotent. Multiple invocations MUST produce the same result as a single invocation. Never introduce side effects that break this guarantee.\n\n");
            for (Element e : collector.idempotent()) {
                FormatterRegistry.idempotent().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.featureFlag().isEmpty()) {
            sb.append("\n## 🚩 FEATURE FLAG GATED CODE\nThe following elements are gated behind a feature flag. Do not assume the flag is always active. Preserve the flag check.\n\n");
            for (Element e : collector.featureFlag()) {
                FormatterRegistry.featureFlag().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.secure().isEmpty()) {
            sb.append("\n## 🔐 SECURITY-CRITICAL CODE\nThe following elements are security-critical. AI must not weaken security properties. Any change must be reviewed for security impact.\n\n");
            for (Element e : collector.secure()) {
                FormatterRegistry.secure().format(e, sb, Platform.CURSOR);
            }
        }

        // New annotations formatting sections for Cursor
        if (!collector.callersOnly().isEmpty()) {
            sb.append("\n## 🚫 ACCESS & CALLS LIMITATIONS\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n");
            for (Element e : collector.callersOnly()) {
                FormatterRegistry.callersOnly().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.sandboxOnly().isEmpty()) {
            sb.append("\n## 🛡️ SANDBOX & TEST HARNESS EXCLUSION\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n");
            for (Element e : collector.sandboxOnly()) {
                FormatterRegistry.sandboxOnly().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.memoryBudget().isEmpty()) {
            sb.append("\n## ⚡ MEMORY ALLOCATION BUDGETS\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n");
            for (Element e : collector.memoryBudget()) {
                FormatterRegistry.memoryBudget().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.pure().isEmpty()) {
            sb.append("\n## 🧠 DETERMINISTIC PURE FUNCTIONS\nThe following elements must remain pure functions without side effects or mutations.\n\n");
            for (Element e : collector.pure()) {
                FormatterRegistry.pure().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.domainModel().isEmpty()) {
            sb.append("\n## 🧱 FRAMEWORK-FREE DOMAIN ENTITIES\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n");
            for (Element e : collector.domainModel()) {
                FormatterRegistry.domainModel().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.extensible().isEmpty()) {
            sb.append("\n## ❄️ open-closed EXTENSION PATTERNS\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n");
            for (Element e : collector.extensible()) {
                FormatterRegistry.extensible().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.inputSanitized().isEmpty()) {
            sb.append("\n## 🚨 MANDATORY INPUT SANITIZATION\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n");
            for (Element e : collector.inputSanitized()) {
                FormatterRegistry.inputSanitized().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.secureLogging().isEmpty()) {
            sb.append("\n## 🔒 SECURE LOGGING MASKING\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n");
            for (Element e : collector.secureLogging()) {
                FormatterRegistry.secureLogging().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.explain().isEmpty()) {
            sb.append("\n## 📋 REQUIRED CHAIN-OF-THOUGHT EXPLANATIONS\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n");
            for (Element e : collector.explain()) {
                FormatterRegistry.explain().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.prototype().isEmpty()) {
            sb.append("\n## 🛠️ EXPERIMENTAL PROTOTYPE STUBS\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n");
            for (Element e : collector.prototype()) {
                FormatterRegistry.prototype().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.sunset().isEmpty()) {
            sb.append("\n## ⚠️ SUNSET DEPRACTED APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n");
            for (Element e : collector.sunset()) {
                FormatterRegistry.sunset().format(e, sb, Platform.CURSOR);
            }
        }

        if (!collector.temporary().isEmpty()) {
            sb.append("\n## 🚧 TEMPORARY CODE WORKAROUNDS\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n");
            for (Element e : collector.temporary()) {
                FormatterRegistry.temporary().format(e, sb, Platform.CURSOR);
            }
        }

        return sb.toString();
    }
}
