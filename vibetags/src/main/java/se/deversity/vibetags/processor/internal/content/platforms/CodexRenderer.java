package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating Codex `AGENTS.md`, config, and rules.
 */
public final class CodexRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        if (platform == Platform.CODEX_CONFIG) {
            return "# " + context.getGeneratedHeader().trim() + "\n[project]\nmodel = \"o3-mini\"\napproval_policy = \"on-request\"\n";
        }
        if (platform == Platform.CODEX_RULES) {
            return "# " + context.getGeneratedHeader().trim() + "\n# VibeTags: Starlark Command Permissions\n\n" +
                "prefix_rule(\"ls\", \"allow\")\n" +
                "prefix_rule(\"cat\", \"allow\")\n" +
                "prefix_rule(\"grep\", \"allow\")\n" +
                "prefix_rule(\"mvn\", \"prompt\")\n" +
                "prefix_rule(\"npm\", \"prompt\")\n" +
                "prefix_rule(\"git\", \"prompt\")\n" +
                "prefix_rule(\"rm\", \"prompt\")\n";
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("# AUTO-GENERATED AI RULES\n").append(context.getGeneratedHeader()).append("# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.CODEX);
        }

        sb.append("\n## CONTEXTUAL RULES\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.CODEX);
        }

        if (!collector.audit().isEmpty()) {
            sb.append("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\n\n");
            for (Element e : collector.audit()) {
                FormatterRegistry.audit().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.ignore().isEmpty()) {
            sb.append("\n## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI context and completions:\n\n");
            for (Element e : collector.ignore()) {
                FormatterRegistry.ignore().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.draft().isEmpty()) {
            sb.append("\n## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
            for (Element e : collector.draft()) {
                FormatterRegistry.draft().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.privacy().isEmpty()) {
            sb.append("\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle PII. Never include their runtime values in logs,\nconsole output, external API calls, test fixtures, or mock data.\n\n");
            for (Element e : collector.privacy()) {
                FormatterRegistry.privacy().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.core().isEmpty()) {
            sb.append("\n## 🧠 CORE FUNCTIONALITY\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
            for (Element e : collector.core()) {
                FormatterRegistry.core().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.performance().isEmpty()) {
            sb.append("\n## ⚡ PERFORMANCE CONSTRAINTS\nHot-path elements — never introduce O(n²) or worse. Always reason about complexity before proposing changes.\n\n");
            for (Element e : collector.performance()) {
                FormatterRegistry.performance().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.contract().isEmpty()) {
            sb.append("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal logic may be modified, but never change method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
            for (Element e : collector.contract()) {
                FormatterRegistry.contract().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.testDriven().isEmpty()) {
            sb.append("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by a matching test update in the same response.\n\n");
            for (Element e : collector.testDriven()) {
                FormatterRegistry.testDriven().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.threadSafe().isEmpty()) {
            sb.append("\n## 🧵 THREAD-SAFE BY DESIGN\nThese elements are explicitly designed to be thread-safe. Preserve the synchronization invariant on every change.\n\n");
            for (Element e : collector.threadSafe()) {
                FormatterRegistry.threadSafe().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.immutable().isEmpty()) {
            sb.append("\n## ❄️ IMMUTABLE TYPES\nThe following types are immutable. Do not introduce non-final fields, setters, or mutating methods.\n\n");
            for (Element e : collector.immutable()) {
                FormatterRegistry.immutable().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.deprecated().isEmpty()) {
            sb.append("\n## ⚠️ DEPRECATED ELEMENTS\nDo not extend these elements. Suggest migrating callers to the listed replacement.\n\n");
            for (Element e : collector.deprecated()) {
                FormatterRegistry.deprecated().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.observability().isEmpty()) {
            sb.append("\n## 📡 OBSERVABILITY INSTRUMENTATION\nThese elements carry instrumentation watched by dashboards/alerts. Do not remove or rename without flagging.\n\n");
            for (Element e : collector.observability()) {
                FormatterRegistry.observability().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.regulation().isEmpty()) {
            sb.append("\n## 📜 REGULATORY COMPLIANCE\nThese elements implement specific compliance clauses. Document compliance impact for every change.\n\n");
            for (Element e : collector.regulation()) {
                FormatterRegistry.regulation().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.parallelTests().isEmpty()) {
            sb.append("\n## 🧪 STRICT TEST ISOLATION\nTests for the following elements must be strictly isolated (no shared mutable state/resources):\n\n");
            for (Element e : collector.parallelTests()) {
                FormatterRegistry.parallelTests().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.legacyBridge().isEmpty()) {
            sb.append("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not restructure or refactor structural patterns of these compatibility bridges:\n\n");
            for (Element e : collector.legacyBridge()) {
                FormatterRegistry.legacyBridge().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.architecture().isEmpty()) {
            sb.append("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layering must be respected. No illegal boundary crossing references:\n\n");
            for (Element e : collector.architecture()) {
                FormatterRegistry.architecture().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.publicApi().isEmpty()) {
            sb.append("\n## 🔌 PUBLIC API SURFACE PROTECTION\nPreserve public signatures, Javadoc, and behavior without breaking backwards compatibility:\n\n");
            for (Element e : collector.publicApi()) {
                FormatterRegistry.publicApi().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.strictExceptions().isEmpty()) {
            sb.append("\n## 🚨 STRICT EXCEPTION HANDLING\nPrecise and robust exception handling required. Generic exception catching/throwing is prohibited:\n\n");
            for (Element e : collector.strictExceptions()) {
                FormatterRegistry.strictExceptions().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.strictTypes().isEmpty()) {
            sb.append("\n## 🏷️ STRICT TYPE SAFETY\nType safety must be strictly preserved. Loose or erased types are prohibited:\n\n");
            for (Element e : collector.strictTypes()) {
                FormatterRegistry.strictTypes().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.internationalized().isEmpty()) {
            sb.append("\n## 🌐 INTERNATIONALIZATION MANDATE\nDo not hardcode user-facing strings. All user-visible text must be localized:\n\n");
            for (Element e : collector.internationalized()) {
                FormatterRegistry.internationalized().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.strictClasspath().isEmpty()) {
            sb.append("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic class loading, custom classloaders, and reflection hacks are prohibited:\n\n");
            for (Element e : collector.strictClasspath()) {
                FormatterRegistry.strictClasspath().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.schemaSafe().isEmpty()) {
            sb.append("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nPreserve database/contract schema and serialization compatibility on every change:\n\n");
            for (Element e : collector.schemaSafe()) {
                FormatterRegistry.schemaSafe().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.idempotent().isEmpty()) {
            sb.append("\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent (multiple invocations = same result as once):\n\n");
            for (Element e : collector.idempotent()) {
                FormatterRegistry.idempotent().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.featureFlag().isEmpty()) {
            sb.append("\n## 🚩 FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume the flag is always active:\n\n");
            for (Element e : collector.featureFlag()) {
                FormatterRegistry.featureFlag().format(e, sb, Platform.CODEX);
            }
        }

        if (!collector.secure().isEmpty()) {
            sb.append("\n## 🔐 SECURITY-CRITICAL CODE\nDo not weaken security properties of these elements. Review every change for security impact:\n\n");
            for (Element e : collector.secure()) {
                FormatterRegistry.secure().format(e, sb, Platform.CODEX);
            }
        }

        return sb.toString();
    }
}
