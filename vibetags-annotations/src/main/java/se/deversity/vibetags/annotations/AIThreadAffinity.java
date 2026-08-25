package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an element is safe on <em>exactly one</em> thread — the inverse of
 * {@link AIThreadSafe}, which promises safety under concurrent access from any thread.
 *
 * <p>These are opposite claims, and the gap between them is a correctness hole rather than a
 * documentation nicety: tagging an EDT-pinned or event-loop-pinned method {@code @AIThreadSafe}
 * states something false, while leaving it untagged invites "let's move this off the main thread".
 * An agent asked to "make this thread-safe" will add a lock — precisely the wrong fix, since the
 * requirement is not mutual exclusion but <em>which</em> thread runs the call.
 *
 * <p>Violations are notoriously schedule-dependent: they pass on a quiet workstation and fail
 * under load, so {@link #symptomIfViolated()} is worth filling in.
 *
 * <pre>{@code
 * @AIThreadAffinity(
 *     value = AIThreadAffinity.Affinity.NAMED,
 *     thread = "Swing EDT",
 *     marshalVia = "SwingUtilities.invokeLater",
 *     symptomIfViolated = "Silent repaint corruption; no exception on most JDKs")
 * public void refreshTable() { }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface AIThreadAffinity {

    /** Which thread an element is pinned to. */
    enum Affinity {
        /** Safe only on the application's main/UI thread. */
        MAIN_ONLY,
        /** Safe on any thread <em>except</em> the main/UI thread — typically because it blocks. */
        NEVER_MAIN,
        /** Safe only on a background/worker thread. */
        BACKGROUND_ONLY,
        /** Pinned to a specific named thread; see {@link AIThreadAffinity#thread()}. */
        NAMED
    }

    /**
     * The affinity this element requires. Deliberately has no default — every value states a
     * different, mutually exclusive constraint, so there is no safe one to assume.
     * @return the required affinity
     */
    Affinity value();

    /**
     * The thread's name when {@link #value()} is {@link Affinity#NAMED} — e.g. {@code "Swing EDT"},
     * {@code "Netty event-loop"}, {@code "GL render thread"}.
     * @return the thread name, or empty string for the non-NAMED affinities
     */
    String thread() default "";

    /**
     * How a caller on the wrong thread should hand work across — e.g.
     * {@code "SwingUtilities.invokeLater"}, {@code "Platform.runLater"}. This is what turns the
     * constraint into an actionable instruction rather than a refusal.
     * @return the marshalling call, or empty string if unspecified
     */
    String marshalVia() default "";

    /**
     * What going wrong looks like — a crash, a hang, silent corruption. Affinity bugs usually
     * reproduce only under load, so naming the symptom is what makes them recognisable.
     * @return the observable symptom, or empty string if unspecified
     */
    String symptomIfViolated() default "";
}
