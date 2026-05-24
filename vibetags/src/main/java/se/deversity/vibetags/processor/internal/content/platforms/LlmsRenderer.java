package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.Collection;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `llms.txt` and `llms-full.txt` discovery formats.
 */
public final class LlmsRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        boolean full = (platform == Platform.LLMS_FULL);
        StringBuilder sb = new StringBuilder(8192);

        // Header
        if (full) {
            sb.append("# ").append(context.getProjectName()).append(" — AI Guardrail Rules\n")
              .append("> Complete AI guardrail configuration generated from source annotations by VibeTags.\n\n")
              .append("This document contains the full set of AI guardrail rules for this project. ")
              .append("AI tools with large context windows (such as Windsurf Cascade, Claude 4.6, or Gemini 1.5 Pro) ")
              .append("may load this file directly instead of fetching individual documentation pages.\n\n");
        } else {
            sb.append("# ").append(context.getProjectName()).append("\n\n")
              .append("> AI guardrail rules generated from source annotations by VibeTags.\n\n")
              .append("AI tools reading this file should respect the guardrails defined below. ")
              .append("These rules were extracted from Java source annotations at compile time ")
              .append("and apply to all AI assistants including Windsurf Cascade, Cursor, Claude, ")
              .append("GitHub Copilot, and Gemini.\n\n");
        }

        // 1. Locked Files
        sb.append(full ? "## Locked Files (Do Not Edit)\nThe following files are locked. AI tools MUST NOT propose modifications to them.\n\n" : "## Locked Files\n");
        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, platform);
        }

        // 2. Contextual Rules
        sb.append(full ? "\n## Contextual Rules\nThese files have specific context and focus areas for AI assistance.\n\n" : "\n## Contextual Rules\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, platform);
        }

        // 3. Security Audits
        appendSection(sb, collector.audit(), platform,
            full ? "\n## Mandatory Security Audit Requirements\nWhen writing or modifying the following files, perform a security audit for the listed vulnerabilities before displaying any code to the user.\n\n" : "\n## Security Audit Requirements\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.audit().format(e, buffer, platform);
                }
            }
        );

        // 4. Ignored Elements
        appendSection(sb, collector.ignore(), platform,
            full ? "\n## Ignored Elements\nThe following elements must be completely excluded from AI context. Treat them as non-existent.\n\n" : "\n## Ignored Elements\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.ignore().format(e, buffer, platform);
                }
            }
        );

        // 5. Draft/TODO
        appendSection(sb, collector.draft(), platform,
            full ? "\n## Implementation Tasks\nThe following elements are in draft mode and need implementation.\n\n" : "\n## Implementation Tasks\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.draft().format(e, buffer, platform);
                }
            }
        );

        // 6. Privacy/PII
        appendSection(sb, collector.privacy(), platform,
            full ? "\n## PII / Privacy Guardrails\nNever include runtime values of the following elements in logs, console output, external API calls, test fixtures, or mock data.\n\n" : "\n## PII / Privacy Guardrails\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.privacy().format(e, buffer, platform);
                }
            }
        );

        // 7. Core
        appendSection(sb, collector.core(), platform,
            full ? "\n## 🧠 Core Functionality\nThe following elements are well-tested core functionality. Make changes with extreme caution.\n\n" : "\n## 🧠 Core Functionality\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.core().format(e, buffer, platform);
                }
            }
        );

        // 8. Performance
        appendSection(sb, collector.performance(), platform,
            full ? "\n## ⚡ Performance Constraints\nThe following elements are on a hot-path and have strict time/space complexity constraints.\n\n" : "\n## ⚡ Performance Constraints\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.performance().format(e, buffer, platform);
                }
            }
        );

        // 9. Contract
        appendSection(sb, collector.contract(), platform,
            full ? "\n## 🔐 Contract-Frozen Signatures\nThe following elements have frozen public API signatures. Internal implementation may be changed, but you MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n" : "\n## 🔐 Contract-Frozen Signatures\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.contract().format(e, buffer, platform);
                }
            }
        );

        // 10. Test-Driven
        appendSection(sb, collector.testDriven(), platform,
            full ? "\n## 🧪 Test-Driven Requirements\nThe following elements require a matching test update whenever their logic is modified. Changes without tests are incomplete.\n\n" : "\n## 🧪 Test-Driven Requirements\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.testDriven().format(e, buffer, platform);
                }
            }
        );

        // 11. Thread Safe
        appendSection(sb, collector.threadSafe(), platform,
            full ? "\n## 🧵 Thread-Safe by Design\nThese elements are explicitly designed to be thread-safe via the named strategy. Preserve the synchronization invariant on every change.\n\n" : "\n## 🧵 Thread-Safe by Design\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.threadSafe().format(e, buffer, platform);
                }
            }
        );

        // 12. Immutable
        appendSection(sb, collector.immutable(), platform,
            full ? "\n## ❄️ Immutable Types\nThe following types are immutable. Never introduce non-final fields, setters, or mutating methods.\n\n" : "\n## ❄️ Immutable Types\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.immutable().format(e, buffer, platform);
                }
            }
        );

        // 13. Deprecated
        appendSection(sb, collector.deprecated(), platform,
            full ? "\n## ⚠️ Deprecated Elements\nThe following elements are deprecated. Suggest migration to the named replacement for any caller and do not extend them.\n\n" : "\n## ⚠️ Deprecated Elements\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.deprecated().format(e, buffer, platform);
                }
            }
        );

        // 14. Observability
        appendSection(sb, collector.observability(), platform,
            full ? "\n## 📡 Observability Instrumentation\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on.\n\n" : "\n## 📡 Observability Instrumentation\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.observability().format(e, buffer, platform);
                }
            }
        );

        // 15. Regulation
        appendSection(sb, collector.regulation(), platform,
            full ? "\n## 📜 Regulatory Compliance\nThe following elements implement specific regulatory clauses. Document compliance impact for every change and never weaken the requirement.\n\n" : "\n## 📜 Regulatory Compliance\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.regulation().format(e, sb, platform);
                }
            }
        );

        // 16. Parallel Tests
        appendSection(sb, collector.parallelTests(), platform,
            full ? "\n## Strict Test Isolation\nAI tools must enforce strict isolation when generating or modifying tests for these elements.\n\n" : "\n## Strict Test Isolation\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.parallelTests().format(e, buffer, platform);
                }
            }
        );

        // 17. Legacy Bridge
        appendSection(sb, collector.legacyBridge(), platform,
            full ? "\n## Legacy Compatibility Bridge\nThese elements are legacy or compatibility bridges. Do not restructure or modernize them.\n\n" : "\n## Legacy Compatibility Bridge\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.legacyBridge().format(e, buffer, platform);
                }
            }
        );

        // 18. Architecture
        appendSection(sb, collector.architecture(), platform,
            full ? "\n## Architectural Boundary Constraints\nStrict architectural layering must be respected. No illegal references or imports.\n\n" : "\n## Architectural Boundary Constraints\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.architecture().format(e, buffer, platform);
                }
            }
        );

        // 19. Public API
        appendSection(sb, collector.publicApi(), platform,
            full ? "\n## Public API Surface Protection\nThese elements expose public API surfaces. Preserve signatures, Javadocs, and backward compatibility.\n\n" : "\n## Public API Surface Protection\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.publicApi().format(e, buffer, platform);
                }
            }
        );

        // 20. Strict Exceptions
        appendSection(sb, collector.strictExceptions(), platform,
            full ? "\n## Strict Exception Handling\nPrecise and robust exception handling must be enforced. No catching or throwing generic Exception.\n\n" : "\n## Strict Exception Handling\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.strictExceptions().format(e, buffer, platform);
                }
            }
        );

        // 21. Strict Types
        appendSection(sb, collector.strictTypes(), platform,
            full ? "\n## Strict Type Safety\nType safety must be strictly preserved. Loose or erased types are prohibited.\n\n" : "\n## Strict Type Safety\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.strictTypes().format(e, buffer, platform);
                }
            }
        );

        // 22. Internationalization
        appendSection(sb, collector.internationalized(), platform,
            full ? "\n## Internationalization Mandate\nUser-facing strings must not be hardcoded; resolve them via localized resources.\n\n" : "\n## Internationalization Mandate\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.internationalized().format(e, buffer, platform);
                }
            }
        );

        // 23. Strict Classpath
        appendSection(sb, collector.strictClasspath(), platform,
            full ? "\n## Strict Classpath Integrity\nDynamic runtime class loading and reflections are strictly prohibited.\n\n" : "\n## Strict Classpath Integrity\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.strictClasspath().format(e, buffer, platform);
                }
            }
        );

        // 24. Schema Safe
        appendSection(sb, collector.schemaSafe(), platform,
            full ? "\n## Schema & Serialization Safety\nSchema and serialization compatibility must be strictly preserved.\n\n" : "\n## Schema & Serialization Safety\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.schemaSafe().format(e, buffer, platform);
                }
            }
        );

        // 25. Idempotent
        appendSection(sb, collector.idempotent(), platform,
            full ? "\n## ♻️ Idempotency Guarantees\nThese operations are idempotent — calling multiple times must produce the same result as calling once.\n\n" : "\n## ♻️ Idempotency Guarantees\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.idempotent().format(e, buffer, platform);
                }
            }
        );

        // 26. Feature Flag
        appendSection(sb, collector.featureFlag(), platform,
            full ? "\n## 🚩 Feature Flag Gated Code\nThese elements are gated behind a feature flag. Preserve the flag check and handle both enabled and disabled code paths.\n\n" : "\n## 🚩 Feature Flag Gated Code\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.featureFlag().format(e, buffer, platform);
                }
            }
        );

        // 27. Secure
        appendSection(sb, collector.secure(), platform,
            full ? "\n## 🔐 Security-Critical Code\nThese elements are security-critical. Do not weaken security properties. Every change requires security review.\n\n" : "\n## 🔐 Security-Critical Code\n",
            new FormatterCaller() {
                @Override public void call(Element e, StringBuilder buffer) {
                    FormatterRegistry.secure().format(e, sb, platform);
                }
            }
        );

        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, Collection<Element> elements, Platform platform, String heading, FormatterCaller caller) {
        if (elements.isEmpty()) return;
        sb.append(heading);
        for (Element e : elements) {
            caller.call(e, sb);
        }
    }

    private interface FormatterCaller {
        void call(Element e, StringBuilder buffer);
    }
}
