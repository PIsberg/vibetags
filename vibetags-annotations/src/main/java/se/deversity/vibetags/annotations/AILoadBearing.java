package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that <em>looks</em> wrong, redundant, or over-defensive but is deliberate, and records
 * the failure that "cleaning it up" would reintroduce.
 *
 * <p>The distinction from its neighbours is the scope of the prohibition. {@link AILocked} forbids
 * <em>all</em> edits; here edits are welcome as long as one invariant survives. {@link AIExplain}
 * demands a rationale <em>from</em> the AI; this supplies one <em>to</em> it. {@code AIContext}'s
 * {@code avoids} is soft prose with no failure mode attached.
 *
 * <p>It also covers the <strong>intentional omission</strong> case that nothing else can express —
 * a decorator deliberately not applied, a resource deliberately not released — by hosting the note
 * on the enclosing element, since there is no element to annotate for something that is not there.
 *
 * <pre>{@code
 * @AILoadBearing(
 *     invariant = "Retired sessions are never deallocated while the dispatch source is live",
 *     breaksIf = "Freeing here reintroduces a use-after-free crash under load (see #412)",
 *     suppressAudit = true)
 * private final List<Session> retained = new ArrayList<>();
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface AILoadBearing {

    /**
     * What must remain true after any change. Required: without it the annotation says only "this
     * is odd", which an agent cannot act on.
     * @return the invariant to preserve
     */
    String invariant();

    /**
     * The concrete failure that breaking the invariant causes — a crash, a leak, a silent desync.
     * Naming the symptom is what stops an agent from "simplifying" anyway.
     * @return the failure mode, or empty string if unspecified
     */
    String breaksIf() default "";

    /**
     * When {@code true}, tells reviewers and audit tooling that the oddity is not a defect and
     * should stop being reported. Use for code that trips a linter or scanner by design.
     * @return whether to suppress audit findings for this element
     */
    boolean suppressAudit() default false;
}
