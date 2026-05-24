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
        StringBuilder sb = new StringBuilder(4096);
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

        return sb.toString();
    }
}
