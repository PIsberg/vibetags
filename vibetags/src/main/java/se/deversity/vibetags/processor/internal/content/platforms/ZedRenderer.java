package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `.rules` for Zed.
 */
public final class ZedRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(context.getGeneratedHeader())
          .append("# Do not edit manually.\n\n## Locked Files (Do Not Modify)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.ZED);
        }

        sb.append("\n## Context Guidelines\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.ZED);
        }

        for (Element e : collector.threadSafe()) {
            FormatterRegistry.threadSafe().format(e, sb, Platform.ZED);
        }
        for (Element e : collector.immutable()) {
            FormatterRegistry.immutable().format(e, sb, Platform.ZED);
        }
        for (Element e : collector.deprecated()) {
            FormatterRegistry.deprecated().format(e, sb, Platform.ZED);
        }
        for (Element e : collector.observability()) {
            FormatterRegistry.observability().format(e, sb, Platform.ZED);
        }
        for (Element e : collector.regulation()) {
            FormatterRegistry.regulation().format(e, sb, Platform.ZED);
        }

        if (!collector.audit().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Security Audits\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n");
            for (Element e : collector.audit()) {
                FormatterRegistry.audit().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.ignore().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n");
            for (Element e : collector.ignore()) {
                FormatterRegistry.ignore().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.draft().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Implementation Tasks\nThe following elements are drafts that need implementation:\n\n");
            for (Element e : collector.draft()) {
                FormatterRegistry.draft().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.privacy().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs runtime values of these elements:\n\n");
            for (Element e : collector.privacy()) {
                FormatterRegistry.privacy().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.core().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n");
            for (Element e : collector.core()) {
                FormatterRegistry.core().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.performance().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n");
            for (Element e : collector.performance()) {
                FormatterRegistry.performance().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.contract().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Contract-Frozen Signatures\nInternal logic may be changed; never alter method names, parameter types, order, return types, or checked exceptions:\n\n");
            for (Element e : collector.contract()) {
                FormatterRegistry.contract().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.testDriven().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Test-Driven Requirements\nChanges to the following elements must be accompanied by matching test code:\n\n");
            for (Element e : collector.testDriven()) {
                FormatterRegistry.testDriven().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.parallelTests().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Strict Test Isolation\nEnforce strict isolation in tests for these elements:\n\n");
            for (Element e : collector.parallelTests()) {
                FormatterRegistry.parallelTests().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.legacyBridge().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
            for (Element e : collector.legacyBridge()) {
                FormatterRegistry.legacyBridge().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.architecture().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Architectural Constraints\nStrict layered architecture constraints apply to these elements:\n\n");
            for (Element e : collector.architecture()) {
                FormatterRegistry.architecture().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.publicApi().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Public API Protection\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
            for (Element e : collector.publicApi()) {
                FormatterRegistry.publicApi().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.strictExceptions().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Strict Exceptions\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
            for (Element e : collector.strictExceptions()) {
                FormatterRegistry.strictExceptions().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.strictTypes().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Strict Types\nLoose typing is strictly prohibited for these elements:\n\n");
            for (Element e : collector.strictTypes()) {
                FormatterRegistry.strictTypes().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.internationalized().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Internationalization\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
            for (Element e : collector.internationalized()) {
                FormatterRegistry.internationalized().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.strictClasspath().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Strict Classpath\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
            for (Element e : collector.strictClasspath()) {
                FormatterRegistry.strictClasspath().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.schemaSafe().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Schema Safety\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
            for (Element e : collector.schemaSafe()) {
                FormatterRegistry.schemaSafe().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.idempotent().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Idempotency\nThese operations must remain idempotent:\n\n");
            for (Element e : collector.idempotent()) {
                FormatterRegistry.idempotent().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.featureFlag().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Feature Flags\nThese elements are behind feature flags — never assume always active:\n\n");
            for (Element e : collector.featureFlag()) {
                FormatterRegistry.featureFlag().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.secure().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Security-Critical Code\nNever weaken security properties of these elements:\n\n");
            for (Element e : collector.secure()) {
                FormatterRegistry.secure().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        // New annotations formatting sections for Zed
        if (!collector.callersOnly().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n");
            for (Element e : collector.callersOnly()) {
                FormatterRegistry.callersOnly().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.sandboxOnly().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n");
            for (Element e : collector.sandboxOnly()) {
                FormatterRegistry.sandboxOnly().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.memoryBudget().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n");
            for (Element e : collector.memoryBudget()) {
                FormatterRegistry.memoryBudget().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.pure().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations.\n\n");
            for (Element e : collector.pure()) {
                FormatterRegistry.pure().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.domainModel().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n");
            for (Element e : collector.domainModel()) {
                FormatterRegistry.domainModel().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.extensible().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n");
            for (Element e : collector.extensible()) {
                FormatterRegistry.extensible().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.inputSanitized().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n");
            for (Element e : collector.inputSanitized()) {
                FormatterRegistry.inputSanitized().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.secureLogging().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n");
            for (Element e : collector.secureLogging()) {
                FormatterRegistry.secureLogging().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.explain().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n");
            for (Element e : collector.explain()) {
                FormatterRegistry.explain().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.prototype().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n");
            for (Element e : collector.prototype()) {
                FormatterRegistry.prototype().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.sunset().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n");
            for (Element e : collector.sunset()) {
                FormatterRegistry.sunset().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        if (!collector.temporary().isEmpty()) {
            StringBuilder sec = new StringBuilder("\n## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n");
            for (Element e : collector.temporary()) {
                FormatterRegistry.temporary().format(e, sec, Platform.ZED);
            }
            sb.append(sec);
        }

        return sb.toString();
    }
}
