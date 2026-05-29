package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `.windsurfrules`.
 */
public final class WindsurfRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(context.getGeneratedHeader())
          .append("# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.WINDSURF);
        }

        sb.append("\n## CONTEXTUAL RULES\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.WINDSURF);
        }

        for (Element e : collector.threadSafe()) {
            FormatterRegistry.threadSafe().format(e, sb, Platform.WINDSURF);
        }
        for (Element e : collector.immutable()) {
            FormatterRegistry.immutable().format(e, sb, Platform.WINDSURF);
        }
        for (Element e : collector.deprecated()) {
            FormatterRegistry.deprecated().format(e, sb, Platform.WINDSURF);
        }
        for (Element e : collector.observability()) {
            FormatterRegistry.observability().format(e, sb, Platform.WINDSURF);
        }
        for (Element e : collector.regulation()) {
            FormatterRegistry.regulation().format(e, sb, Platform.WINDSURF);
        }

        if (!collector.audit().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code.\n\n");
            for (Element e : collector.audit()) {
                FormatterRegistry.audit().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.ignore().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n");
            for (Element e : collector.ignore()) {
                FormatterRegistry.ignore().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.draft().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n");
            for (Element e : collector.draft()) {
                FormatterRegistry.draft().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.privacy().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🔒 PII / PRIVACY GUARDRAILS\nNever include runtime values of the following in logs, console output, external API calls, test fixtures, or mock data.\n\n");
            for (Element e : collector.privacy()) {
                FormatterRegistry.privacy().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.core().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
            for (Element e : collector.core()) {
                FormatterRegistry.core().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.performance().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) or worse complexity. Always reason about time/space before proposing changes.\n\n");
            for (Element e : collector.performance()) {
                FormatterRegistry.performance().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.contract().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
            for (Element e : collector.contract()) {
                FormatterRegistry.contract().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.testDriven().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nAI MUST NOT propose changes to the following elements without also providing the matching test code.\n\n");
            for (Element e : collector.testDriven()) {
                FormatterRegistry.testDriven().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.parallelTests().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🧪 STRICT TEST ISOLATION\nEnforce strict isolation for tests generated or modified for these elements:\n\n");
            for (Element e : collector.parallelTests()) {
                FormatterRegistry.parallelTests().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.legacyBridge().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
            for (Element e : collector.legacyBridge()) {
                FormatterRegistry.legacyBridge().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.architecture().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layered architecture constraints apply to these elements:\n\n");
            for (Element e : collector.architecture()) {
                FormatterRegistry.architecture().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.publicApi().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🔌 PUBLIC API SURFACE PROTECTION\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
            for (Element e : collector.publicApi()) {
                FormatterRegistry.publicApi().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.strictExceptions().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🚨 STRICT EXCEPTION HANDLING\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
            for (Element e : collector.strictExceptions()) {
                FormatterRegistry.strictExceptions().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.strictTypes().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🏷️ STRICT TYPE SAFETY\nLoose typing is strictly prohibited for these elements:\n\n");
            for (Element e : collector.strictTypes()) {
                FormatterRegistry.strictTypes().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.internationalized().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🌐 INTERNATIONALIZATION MANDATE\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
            for (Element e : collector.internationalized()) {
                FormatterRegistry.internationalized().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.strictClasspath().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
            for (Element e : collector.strictClasspath()) {
                FormatterRegistry.strictClasspath().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.schemaSafe().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
            for (Element e : collector.schemaSafe()) {
                FormatterRegistry.schemaSafe().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.idempotent().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — multiple calls = same as one call:\n\n");
            for (Element e : collector.idempotent()) {
                FormatterRegistry.idempotent().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.featureFlag().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🚩 FEATURE FLAG GATED CODE\nNever assume these feature flags are always active — preserve all flag checks:\n\n");
            for (Element e : collector.featureFlag()) {
                FormatterRegistry.featureFlag().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.secure().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🔐 SECURITY-CRITICAL CODE\nNever weaken security properties of these elements. Every change requires security review:\n\n");
            for (Element e : collector.secure()) {
                FormatterRegistry.secure().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        // New annotations formatting sections for Windsurf
        if (!collector.callersOnly().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🚫 ACCESS & CALLS LIMITATIONS\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n");
            for (Element e : collector.callersOnly()) {
                FormatterRegistry.callersOnly().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.sandboxOnly().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🛡️ SANDBOX & TEST HARNESS EXCLUSION\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n");
            for (Element e : collector.sandboxOnly()) {
                FormatterRegistry.sandboxOnly().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.memoryBudget().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## ⚡ MEMORY ALLOCATION BUDGETS\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n");
            for (Element e : collector.memoryBudget()) {
                FormatterRegistry.memoryBudget().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.pure().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🧠 DETERMINISTIC PURE FUNCTIONS\nThe following elements must remain pure functions without side effects or mutations.\n\n");
            for (Element e : collector.pure()) {
                FormatterRegistry.pure().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.domainModel().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🧱 FRAMEWORK-FREE DOMAIN ENTITIES\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n");
            for (Element e : collector.domainModel()) {
                FormatterRegistry.domainModel().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.extensible().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## ❄️ open-closed EXTENSION PATTERNS\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n");
            for (Element e : collector.extensible()) {
                FormatterRegistry.extensible().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.inputSanitized().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🚨 MANDATORY INPUT SANITIZATION\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n");
            for (Element e : collector.inputSanitized()) {
                FormatterRegistry.inputSanitized().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.secureLogging().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🔒 SECURE LOGGING MASKING\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n");
            for (Element e : collector.secureLogging()) {
                FormatterRegistry.secureLogging().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.explain().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 📋 REQUIRED CHAIN-OF-THOUGHT EXPLANATIONS\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n");
            for (Element e : collector.explain()) {
                FormatterRegistry.explain().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.prototype().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🛠️ EXPERIMENTAL PROTOTYPE STUBS\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n");
            for (Element e : collector.prototype()) {
                FormatterRegistry.prototype().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.sunset().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## ⚠️ SUNSET DEPRACTED APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n");
            for (Element e : collector.sunset()) {
                FormatterRegistry.sunset().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        if (!collector.temporary().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## 🚧 TEMPORARY CODE WORKAROUNDS\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n");
            for (Element e : collector.temporary()) {
                FormatterRegistry.temporary().format(e, sec, Platform.WINDSURF);
            }
            sb.append(sec);
        }

        return sb.toString();
    }
}
