package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import se.deversity.vibetags.processor.model.GuardrailAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A constructor can carry a guardrail, and the guardrail reaches the generated file.
 *
 * <p>Until #488 no annotation declared {@code ElementType.CONSTRUCTOR}, so a constructor could
 * not be guarded at all. That was odd rather than obviously wrong: {@code ElementNaming} has
 * always rendered constructors, because javac hands them to the collector as enclosed elements of
 * an annotated <em>type</em>, and {@code ElementNamingFormatParityTest} covers the shape. So
 * constructors were visible to the renderer but not addressable by an author, and the way anybody
 * found out was a compiler error.
 *
 * <p>A constructor is where invariants are established, which makes it exactly the place an
 * author wants to say "this signature is frozen" or "do not reorder this initialisation". The
 * alternative was locking the whole enclosing type, which is a much bigger hammer than the
 * problem.
 *
 * <p>The end-to-end case matters more than the target check: adding a target only means the
 * annotation compiles there. It has to survive collection, rendering and the write to actually
 * be a guardrail, and a constructor takes a different path through {@code ElementNaming} from a
 * method.
 */
class ConstructorLevelGuardrailTest {

    /**
     * The two that deliberately do not accept a constructor, and why. Stated here rather than
     * left as an absence, so a future sweep that "completes" the set has to argue with this.
     *
     * <p>{@code @AIPure} forbids assignment to enclosing state, which is precisely what a
     * constructor is for. {@code @AIIdempotent} says repeated invocations produce the result of
     * one, and constructing twice produces two objects by design.
     */
    private static final List<String> DELIBERATELY_NOT_ON_CONSTRUCTORS =
        List.of("AIIdempotent", "AIPure");

    @Test
    @DisplayName("every method-level annotation accepts a constructor, except the two that cannot mean anything there")
    void methodLevelAnnotationsAlsoTargetConstructors() {
        List<String> missing = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();

        for (Class<? extends java.lang.annotation.Annotation> type : GuardrailAnnotations.ALL) {
            Target target = type.getAnnotation(Target.class);
            if (target == null) {
                continue;
            }
            List<ElementType> targets = List.of(target.value());
            if (!targets.contains(ElementType.METHOD)) {
                continue;
            }
            boolean allowed = targets.contains(ElementType.CONSTRUCTOR);
            boolean excluded = DELIBERATELY_NOT_ON_CONSTRUCTORS.contains(type.getSimpleName());
            if (!allowed && !excluded) {
                missing.add(type.getSimpleName());
            }
            if (allowed && excluded) {
                unexpected.add(type.getSimpleName());
            }
        }

        assertEquals(List.of(), missing,
            "these annotations can be written on a method but not on a constructor. A constructor "
                + "is a member like any other and is where invariants are established; if one of "
                + "these genuinely cannot mean anything there, add it to "
                + "DELIBERATELY_NOT_ON_CONSTRUCTORS with the reason rather than leaving the gap");
        assertEquals(List.of(), unexpected,
            "these are listed as deliberately not applicable to constructors but now accept one. "
                + "Either the reasoning changed and the list should say so, or the target was "
                + "added by a sweep that did not read it");
    }

    @Test
    @DisplayName("a guardrail on a constructor reaches the generated file")
    void constructorGuardrailIsRendered(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.touchOptIn("CLAUDE.md");
        harness.addSource("com.example.Account", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            import se.deversity.vibetags.annotations.AIContract;
            public class Account {
                @AILocked(reason = "initialisation order is load-bearing")
                public Account(String id, long balance) {
                }

                @AIContract(reason = "callers depend on this shape")
                public Account(String id) {
                }
            }
            """);

        harness.compile();

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(claude.contains("initialisation order is load-bearing"),
            "the @AILocked reason on a constructor never reached CLAUDE.md:\n" + claude);

        // Rendered under the constructor's own path, not the enclosing type's: javac names a
        // constructor after its class, so the two overloads must be distinguishable by their
        // parameter lists or the guardrail addresses the wrong one.
        assertTrue(claude.contains("Account(java.lang.String,long)"),
            "the constructor was not addressed by its own signature. Two overloads that render "
                + "identically point an agent at whichever one it happens to open:\n" + claude);
    }
}
