package se.deversity.vibetags.processor.internal.validation;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.annotations.AIThreadSafe;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rules that read an annotation's own attributes: a required string left blank, an array left
 * empty, a number outside its range, a date already in the past.
 *
 * <p>The recurring failure these catch is an annotation that compiles, appears in the generated
 * guardrail file, and instructs nobody — {@code @AIAudit} with no {@code checkFor} list is
 * indistinguishable from no annotation at all once it reaches the agent. Warning at compile time is
 * the only moment where the author is still looking at the code.
 */
public final class CoreRules {

    /** ISO date for {@code @AITemporary.expiresOn}. Compiled once, not once per annotated element. */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private CoreRules() {
    }

    /** Every attribute-completeness rule, in the order they were historically reported. */
    public static List<ValidationRule> all() {
        return List.of(
            AttributeRule.of(AIContext.class, (ctx, e, a) -> {
                if (a.focus().isBlank() && a.avoids().isBlank()) {
                    ctx.warn(e, "@AIContext on " + e
                        + " has blank 'focus' and 'avoids' attributes. The annotation will be ignored.");
                }
            }),

            AttributeRule.of(AIAudit.class, (ctx, e, a) -> {
                if (a.checkFor().length == 0) {
                    ctx.warn(e, "@AIAudit on " + e
                        + " has no 'checkFor' items list. It will be ignored.");
                }
            }),

            AttributeRule.of(AITestDriven.class, (ctx, e, a) -> {
                if (a.coverageGoal() < 0 || a.coverageGoal() > 100) {
                    ctx.warn(e, "@AITestDriven on " + e
                        + " has an invalid coverageGoal (" + a.coverageGoal() + "). "
                        + "Value must be between 0 and 100 (inclusive).");
                }
            }),

            // Reported against the offending field rather than the type: the fix is on that line.
            AttributeRule.of(AIImmutable.class, (ctx, type, a) -> {
                for (Element enclosed : type.getEnclosedElements()) {
                    if (enclosed.getKind() != ElementKind.FIELD) {
                        continue;
                    }
                    if (enclosed.getModifiers().contains(Modifier.STATIC)) {
                        continue;
                    }
                    if (!enclosed.getModifiers().contains(Modifier.FINAL)) {
                        ctx.warn(enclosed, "@AIImmutable on " + type
                            + " but field '" + enclosed.getSimpleName() + "' is not final. "
                            + "Immutable types must declare all instance fields final.");
                    }
                }
            }),

            AttributeRule.of(AIDeprecated.class, (ctx, e, a) -> {
                if (a.replacedBy().isBlank()) {
                    ctx.warn(e, "@AIDeprecated on " + e
                        + " has no 'replacedBy' target. AI assistants will flag the element as deprecated "
                        + "but cannot route callers to a replacement. Add replacedBy = \"com.example.NewClass\" "
                        + "to make the migration actionable.");
                }
            }),

            AttributeRule.of(AIThreadSafe.class, (ctx, e, a) -> {
                if (a.strategy() == AIThreadSafe.Strategy.IMMUTABLE
                        && e.getAnnotation(AIImmutable.class) != null) {
                    ctx.warn(e, e + " is annotated with @AIThreadSafe(IMMUTABLE) and @AIImmutable. "
                        + "Use @AIImmutable alone — immutability already implies thread-safety.");
                }
            }),

            AttributeRule.of(AIObservability.class, (ctx, e, a) -> {
                if (a.metrics().length == 0 && a.traces().length == 0 && a.logs().length == 0) {
                    ctx.warn(e, "@AIObservability on " + e
                        + " declares no metrics, traces, or logs. The annotation will be ignored.");
                }
            }),

            AttributeRule.of(AIRegulation.class, (ctx, e, a) -> {
                if (a.standard() == null || a.standard().isBlank()) {
                    ctx.warn(e, "@AIRegulation on " + e
                        + " has a blank 'standard' attribute. Name the compliance standard "
                        + "(e.g., GDPR, PCI-DSS, HIPAA).");
                }
            }),

            AttributeRule.of(AIFeatureFlag.class, (ctx, e, a) -> {
                if (a.flag() == null || a.flag().isBlank()) {
                    ctx.warn(e, "@AIFeatureFlag on " + e
                        + " has a blank 'flag' attribute. Specify the feature flag key "
                        + "(e.g., @AIFeatureFlag(flag = \"my.feature.enabled\")).");
                }
            }),

            AttributeRule.of(AISecure.class, (ctx, e, a) -> {
                if (a.aspect() == null || a.aspect().isBlank()) {
                    ctx.warn(e, "@AISecure on " + e
                        + " has a blank 'aspect' attribute. Consider specifying the security concern "
                        + "(e.g., 'authentication', 'encryption', 'authorization').");
                }
            }),

            AttributeRule.of(AISunset.class, (ctx, e, a) -> {
                if (a.jira() == null || a.jira().isBlank()) {
                    ctx.warn(e, "@AISunset on " + e
                        + " has a blank 'jira' attribute. Specify the JIRA issue ticket key.");
                }
            }),

            AttributeRule.of(AITemporary.class, CoreRules::checkTemporary),

            AttributeRule.of(AIGenerated.class, (ctx, e, a) -> {
                if (a.regenerateWith().isBlank() && a.editInstead().isBlank()) {
                    ctx.warn(e, "@AIGenerated on " + e
                        + " names a source but no route back to it. Set 'regenerateWith' or 'editInstead' "
                        + "so the guardrail is a redirect rather than a dead end.");
                }
            }),

            AttributeRule.of(AILoadBearing.class, (ctx, e, a) -> {
                if (a.breaksIf().isBlank()) {
                    ctx.warn(e, "@AILoadBearing on " + e
                        + " has a blank 'breaksIf' attribute. Naming the concrete failure (crash, leak, "
                        + "silent desync) is what stops the code being 'simplified' anyway.");
                }
                if (a.suppressAudit() && e.getAnnotation(AIAudit.class) != null) {
                    ctx.warn(e, e + " is annotated with both @AILoadBearing(suppressAudit = true) and @AIAudit. "
                        + "This is contradictory: one suppresses audit findings, the other mandates them.");
                }
            }),

            AttributeRule.of(AIBannedApi.class, (ctx, e, a) -> {
                if (a.forbidden().length == 0) {
                    ctx.warn(e, "@AIBannedApi on " + e
                        + " lists no forbidden APIs, so it bans nothing. Populate 'forbidden' or remove the annotation.");
                }
                if (a.forbidden().length > 0 && a.useInstead().isBlank()) {
                    ctx.warn(e, "@AIBannedApi on " + e
                        + " has a blank 'useInstead' attribute. A ban with no sanctioned route usually "
                        + "produces a worse substitute rather than the right one.");
                }
            }),

            AttributeRule.of(AIThreadAffinity.class, (ctx, e, a) -> {
                if (a.value() == AIThreadAffinity.Affinity.NAMED && a.thread().isBlank()) {
                    ctx.warn(e, "@AIThreadAffinity on " + e
                        + " uses Affinity.NAMED but leaves 'thread' blank, so the required thread is unidentifiable. "
                        + "Name it (e.g. thread = \"Swing EDT\") or use a specific affinity constant.");
                }
                if (a.marshalVia().isBlank()) {
                    ctx.warn(e, "@AIThreadAffinity on " + e
                        + " has a blank 'marshalVia' attribute. Without it a caller on the wrong thread is told "
                        + "'no' with no way to comply.");
                }
            }),

            AttributeRule.of(AIKeepInSync.class, (ctx, e, a) -> {
                if (a.mirrors().length == 0) {
                    ctx.warn(e, "@AIKeepInSync on " + e
                        + " lists no mirrors, so nothing is kept in sync. Populate 'mirrors' or remove the annotation.");
                }
            })
        );
    }

    /**
     * {@code @AITemporary} is the one rule that fires on a build that used to be clean: the date
     * passes and the workaround is now overdue, whether or not anybody edited the file.
     */
    private static void checkTemporary(ValidationContext ctx, Element e, AITemporary temp) {
        if (temp.expiresOn() == null || temp.expiresOn().isBlank()) {
            ctx.warn(e, "@AITemporary on " + e
                + " has a blank 'expiresOn' attribute. Specify an ISO date (YYYY-MM-DD).");
            return;
        }
        String expiresOn = temp.expiresOn().trim();
        if (!ISO_DATE.matcher(expiresOn).matches()) {
            ctx.warn(e, "@AITemporary on " + e
                + " has an invalid 'expiresOn' date format: '" + expiresOn + "'. Must be YYYY-MM-DD.");
            return;
        }
        try {
            // The developer's own calendar day is the right clock here: "this workaround was
            // supposed to be gone by now" is a statement about the person reading the warning, not
            // about UTC. Stated explicitly rather than left to LocalDate.now()'s hidden default.
            if (LocalDate.now(ZoneId.systemDefault()).isAfter(LocalDate.parse(expiresOn))) {
                ctx.warn(e, "Temporary logic in " + e
                    + " has expired on " + expiresOn + ". Reason: " + temp.reason() + ". Clean up immediately.");
            }
        } catch (DateTimeParseException ex) {
            // Matches the ISO shape but is not a real date — 2026-02-31, say.
            ctx.warn(e, "@AITemporary on " + e
                + " has an unparseable 'expiresOn' date: '" + expiresOn + "'.");
        }
    }
}
