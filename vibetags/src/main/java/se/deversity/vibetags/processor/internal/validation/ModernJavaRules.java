package se.deversity.vibetags.processor.internal.validation;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIThreadSafe;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.type.TypeKind;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Detectors that only make sense once the language grew past Java 8.
 *
 * <p>Every other rule in this package compares annotations against each other. These compare an
 * annotation against the <em>declaration</em> it sits on, and each one exists because a modern
 * construct changed what that declaration already guarantees — or already forbids:
 *
 * <ul>
 *   <li><strong>records</strong> (16) make fields final for you, so {@code @AIImmutable} on a record
 *       reads as settled when the array component underneath it is still freely mutable;</li>
 *   <li><strong>sealed types</strong> (17) make "extend this" a statement that can be false at the
 *       language level rather than merely unwise;</li>
 *   <li><strong>virtual threads</strong> (21) turn a {@code ThreadLocal} strategy from bounded
 *       per-pool state into unbounded per-task state, which is what {@code ScopedValue} (25,
 *       <a href="https://openjdk.org/jeps/506">JEP 506</a>) replaced it with;</li>
 *   <li><strong>compact source files</strong> (25,
 *       <a href="https://openjdk.org/jeps/512">JEP 512</a>) put a class in the unnamed package,
 *       and VibeTags identifies a guarded element by its fully-qualified name.</li>
 * </ul>
 *
 * <p>All of it is read off {@code javax.lang.model} alone. Nothing here needs the Tree API, so
 * unlike {@link ArchitectureRule} these still fire under Gradle's compiler.
 */
public final class ModernJavaRules {

    private ModernJavaRules() {
    }

    /** Every modern-Java detector. */
    public static List<ValidationRule> all() {
        List<ValidationRule> rules = new ArrayList<>();
        rules.add(AttributeRule.of(AIImmutable.class, ModernJavaRules::checkShallowImmutability));
        rules.add(AttributeRule.of(AIExtensible.class, ModernJavaRules::checkExtensible));
        rules.add(AttributeRule.of(AIPure.class, ModernJavaRules::checkPure));
        rules.add(AttributeRule.of(AIPublicAPI.class, ModernJavaRules::checkPublicApi));
        rules.add(AttributeRule.of(AIThreadSafe.class, ModernJavaRules::checkThreadLocalStrategy));
        // The unnamed-package hazard is about a guarded element's identity, so it is checked for the
        // three families whose identity is load-bearing: they key the enforcement baseline. Bound to
        // annotations the registry already scans, so this costs no extra round query.
        rules.add(new UnnamedPackageRule(AILocked.class));
        rules.add(new UnnamedPackageRule(AIContract.class));
        rules.add(new UnnamedPackageRule(AIPublicAPI.class));
        return List.copyOf(rules);
    }

    /**
     * {@code @AIImmutable} with an array-typed instance field. {@code final} on an array reference
     * freezes the reference, not the contents, so a caller handed the field (or a record's
     * generated accessor) can still write through it. Records make this easy to miss precisely
     * because they remove the usual reminder that a field needs a defensive copy.
     */
    private static void checkShallowImmutability(ValidationContext ctx, Element type, AIImmutable a) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD || enclosed.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (enclosed.asType().getKind() != TypeKind.ARRAY) {
                continue;
            }
            ctx.warn(enclosed, "@AIImmutable on " + type + " but field '" + enclosed.getSimpleName()
                + "' is an array. 'final' freezes the reference, not the elements"
                + (type.getKind() == ElementKind.RECORD
                    ? ", and the record's generated accessor hands that array straight out"
                    : "")
                + ". Copy on the way in and on the way out, or hold a List.");
        }
    }

    /**
     * {@code @AIExtensible} on a declaration the language will not let anybody extend. The
     * annotation's whole job is to tell an agent "add behaviour here rather than editing this" —
     * on a final, record, enum or sealed type that instruction cannot be followed.
     */
    private static void checkExtensible(ValidationContext ctx, Element type, AIExtensible a) {
        String kindWord = switch (type.getKind()) {
            case RECORD -> "a record";
            case ENUM -> "an enum";
            default -> null;
        };
        if (kindWord != null) {
            ctx.warn(type, "@AIExtensible on " + type + " but it is " + kindWord
                + ", which is implicitly final. Nothing can extend it, so the "
                + a.value() + " route the annotation asks for is not open. "
                + "Extract the extension point into an interface, or drop the annotation.");
            return;
        }
        if (type.getModifiers().contains(Modifier.FINAL)) {
            ctx.warn(type, "@AIExtensible on " + type + " but the type is declared final. "
                + "Nothing can extend it, so the " + a.value()
                + " route the annotation asks for is not open. Remove 'final' or drop the annotation.");
            return;
        }
        if (type.getModifiers().contains(Modifier.SEALED)) {
            ctx.warn(type, "@AIExtensible on " + type + " but the type is sealed. "
                + "Only its permitted subtypes can extend it, so adding a " + a.value()
                + " implementation also means editing the permits clause — which is exactly the "
                + "central edit the annotation is meant to avoid. Say so in the guardrail, or unseal the type.");
        }
    }

    /**
     * {@code @AIPure} on a {@code void} method. A method with no return value and no side effects
     * has no observable effect at all, so either the annotation is wrong or the method is dead.
     */
    private static void checkPure(ValidationContext ctx, Element method, AIPure a) {
        if (method instanceof ExecutableElement executable
                && executable.getReturnType().getKind() == TypeKind.VOID) {
            ctx.warn(method, "@AIPure on " + method + " but the method returns void. "
                + "A pure method with no return value has no observable effect — either it mutates "
                + "something (and is not pure) or it does nothing. Return the computed value, or drop the annotation.");
        }
    }

    /**
     * {@code @AIPublicAPI} on something no external caller can reach. The annotation is a promise
     * about a published surface; on a package-private element it promises nothing, and under the
     * enforcing mode it would guard a signature that is free to change.
     */
    private static void checkPublicApi(ValidationContext ctx, Element element, AIPublicAPI a) {
        Element barrier = firstNonPublicEnclosure(element);
        if (barrier == null) {
            return;
        }
        String where = barrier.equals(element)
            ? "it is not public"
            : "the enclosing " + barrier + " is not public";
        ctx.warn(element, "@AIPublicAPI on " + element + " but " + where
            + ", so no caller outside the package can bind to it. Make the declaration public, "
            + "or use @AIContract, which is about a signature rather than a published surface.");
    }

    /**
     * {@code @AIThreadSafe(THREAD_LOCAL)} on a type that really does hold {@code ThreadLocal}
     * state. Under virtual threads that stops being a bounded per-pool cache and becomes one copy
     * per task, which is the retention problem {@code ScopedValue} (JDK 25, JEP 506) exists to
     * solve. Advisory, not a warning: the strategy is still correct, it just no longer scales the
     * way it did when threads were the expensive thing.
     */
    private static void checkThreadLocalStrategy(ValidationContext ctx, Element type, AIThreadSafe a) {
        if (a.strategy() != AIThreadSafe.Strategy.THREAD_LOCAL) {
            return;
        }
        Element field = firstThreadLocalField(type);
        if (field == null) {
            return;
        }
        // One note per type, not one per field: the point is the strategy, not the field count.
        ctx.note(type, "@AIThreadSafe(THREAD_LOCAL) on " + type + " with the ThreadLocal field '"
            + field.getSimpleName() + "'. Under virtual threads that is one copy per task "
            + "rather than one per pooled thread. If the value is a per-request constant, "
            + "ScopedValue (JDK 25, JEP 506) binds it for the call and releases it on return.");
    }

    /** The first {@code ThreadLocal}-typed field declared on {@code type}, or {@code null}. */
    private static @Nullable Element firstThreadLocalField(Element type) {
        Element found = null;
        for (Element enclosed : type.getEnclosedElements()) {
            if (found == null
                    && enclosed.getKind() == ElementKind.FIELD
                    && enclosed.asType().toString().startsWith("java.lang.ThreadLocal")) {
                found = enclosed;
            }
        }
        return found;
    }

    /**
     * The innermost declaration on {@code element}'s enclosing chain that is not {@code public}, or
     * {@code null} when the whole chain up to the package is public.
     */
    private static @Nullable Element firstNonPublicEnclosure(Element element) {
        for (Element e = element; e != null && e.getKind() != ElementKind.PACKAGE; e = e.getEnclosingElement()) {
            if (!e.getModifiers().contains(Modifier.PUBLIC)) {
                return e;
            }
        }
        return null;
    }

    /**
     * A guarded element declared in the unnamed package — which since JDK 25's compact source files
     * is something you can reach without meaning to. VibeTags identifies an element by its
     * fully-qualified name, and in the unnamed package that name is just the simple one, so two
     * files declaring {@code Main} produce one colliding guardrail entry rather than two.
     */
    private static final class UnnamedPackageRule implements ValidationRule {

        private final Class<? extends Annotation> scans;

        UnnamedPackageRule(Class<? extends Annotation> scans) {
            this.scans = scans;
        }

        @Override
        public Class<? extends Annotation> scans() {
            return scans;
        }

        @Override
        public void check(ValidationContext ctx, Element element) {
            Element e = element;
            while (e != null && e.getKind() != ElementKind.PACKAGE) {
                e = e.getEnclosingElement();
            }
            if (e instanceof PackageElement pkg && pkg.isUnnamed()) {
                ctx.warn(element, "@" + scans.getSimpleName() + " on " + element
                    + ", which is in the unnamed package. VibeTags identifies a guarded element by its "
                    + "fully-qualified name, so two unnamed-package declarations that share a simple name "
                    + "collide into one guardrail entry — and one enforcement-baseline key. Declare a package.");
            }
        }
    }
}
