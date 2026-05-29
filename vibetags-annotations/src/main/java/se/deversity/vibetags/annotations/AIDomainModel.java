package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces Domain-Driven Design (DDD) boundaries by preventing external/framework imports.
 * The compiler will scan and block any imports from Spring, JPA/Hibernate, Jackson, etc. unless explicitly whitelisted.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AIDomainModel {
    /**
     * Optional list of packages or classes that are explicitly allowed to be imported.
     */
    String[] allow() default {};
}
