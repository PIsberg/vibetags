package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated element carries active observability instrumentation (metrics,
 * traces, log statements) that downstream dashboards and alerts depend on.
 *
 * <p>AI assistants must not remove or rename any metric, trace span, or log statement on this
 * element without flagging the corresponding dashboard/alert update.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIObservability {

    /**
     * Names of metric counters/gauges that this element publishes.
     * @return the metric names, or empty array if none
     */
    String[] metrics() default {};

    /**
     * Names of trace spans this element opens.
     * @return the trace span names, or empty array if none
     */
    String[] traces() default {};

    /**
     * Identifying tokens or fields of the log statements this element emits.
     * @return the log statement identifiers, or empty array if none
     */
    String[] logs() default {};

    /**
     * Optional free-form note describing the dashboard or alert that depends on this
     * instrumentation.
     * @return the note, or empty string if none
     */
    String note() default "";
}
