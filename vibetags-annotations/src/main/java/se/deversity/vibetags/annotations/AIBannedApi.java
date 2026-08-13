package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forbids named APIs at this element even though they compile, and names the sanctioned route.
 *
 * <p>The constraint is hosted on the <em>consumer</em> and points outward, which is the whole
 * reason this cannot be expressed with {@link AIDeprecated}: that annotates the old element, but
 * the symbols teams actually ban — {@code java.util.Date}, {@code System.out}, a framework's
 * {@code @Scheduled} — are stdlib or third-party and cannot be annotated at all.
 * {@code AIArchitecture}'s {@code cannotReference} bans a <em>layer</em> rather than a
 * <em>symbol</em>, and carries no replacement.
 *
 * <pre>{@code
 * @AIBannedApi(
 *     forbidden = {"java.lang.System.out", "java.lang.System.err"},
 *     useInstead = "the injected org.slf4j.Logger",
 *     reason = "Console output bypasses structured logging and is invisible in production")
 * public class OrderService { }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
public @interface AIBannedApi {

    /**
     * The forbidden symbols, types, or packages — fully qualified where ambiguous. Required, and
     * must be non-empty: a ban with nothing banned is a no-op the processor warns about.
     * @return the forbidden API names
     */
    String[] forbidden();

    /**
     * The sanctioned replacement. Strongly recommended — a ban without a route is a dead end, and
     * an agent denied its first choice will usually invent a worse second one.
     * @return the approved alternative, or empty string if unspecified
     */
    String useInstead() default "";

    /**
     * Why the API is banned here. Turns an arbitrary-looking prohibition into one an agent can
     * reason about when it meets an unlisted but equivalent symbol.
     * @return the rationale, or empty string if unspecified
     */
    String reason() default "";
}
