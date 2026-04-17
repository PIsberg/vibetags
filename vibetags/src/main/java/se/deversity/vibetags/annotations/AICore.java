package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Instructs AI assistants that this is well-tested core functionality
 * which could be sensitive to changes. Make modifications with extreme caution.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AICore {
    /**
     * Level of sensitivity or impact of changes (e.g. "High", "Critical").
     * @return the sensitivity level
     */
    String sensitivity() default "High";
    
    /**
     * Specific note or warning regarding why this core logic is sensitive.
     * @return the warning note
     */
    String note() default "Well-tested core functionality. Make changes with extreme caution.";
}
