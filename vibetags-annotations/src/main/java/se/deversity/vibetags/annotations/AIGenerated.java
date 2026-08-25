package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks machine-generated code whose hand edits are silently overwritten, and names the route a
 * change should take instead.
 *
 * <p>This is a <em>redirect</em>, not a wall. {@link AILocked} can only say "stop", which makes an
 * agent either give up or route around the obstacle; {@code @AIGenerated} says "stop, and here is
 * where the change actually belongs". {@link AIIgnore} is the wrong tool for generated code in the
 * opposite way: an agent must still <em>read</em> generated types to understand behaviour, it must
 * only never <em>write</em> them.
 *
 * <p>Typical targets: JOOQ/JPA metamodels, protobuf and OpenAPI stubs, annotation-processor output,
 * anything under a {@code generated/} source root.
 *
 * <pre>{@code
 * @AIGenerated(
 *     from = "src/main/resources/openapi/orders.yaml",
 *     regenerateWith = "mvn generate-sources",
 *     editInstead = "src/main/resources/openapi/orders.yaml")
 * public class OrdersApiStub { }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface AIGenerated {

    /**
     * The true source this element is generated from — a schema, template, IDL file, or upstream
     * repository. Required: an agent that does not know the source cannot take the redirect.
     * @return the generating source
     */
    String from();

    /**
     * The command that regenerates this element (e.g. {@code "mvn generate-sources"}).
     * @return the regeneration command, or empty string if unspecified
     */
    String regenerateWith() default "";

    /**
     * The file a human should actually change, when it differs from {@link #from()} — for example
     * a template that feeds the generator rather than the schema it reads.
     * @return the file to edit instead, or empty string if {@link #from()} already says it
     */
    String editInstead() default "";
}
