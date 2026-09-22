package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.GuardrailContentBuilder;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptor;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptors;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every implicit activation in the descriptor table is one the builder actually performs.
 *
 * <p>Five outputs have no opt-in file of their own: the two Codex sidecars and the three
 * always-loaded safety files that sit inside a rules directory. {@code PlatformDescriptors.ALL}
 * records which service activates each of them, in {@code implicitParent}, and
 * {@code ServiceRegistry.optInKeys()} is derived from that field. What acted on the relationship
 * was a hand-written {@code if} per child in {@code GuardrailContentBuilder.build()}, so the table
 * named the parent and the builder named it again (issue #830).
 *
 * <p>The failure that pairing allows is silent in both directions. A sixth child added to the
 * table with no {@code if} beside it is simply never rendered: no file, no warning, and
 * {@code optInKeys()} correctly refuses to let the user ask for it by hand. An {@code if} left
 * behind after its entry changed parents renders the child on the wrong signal. Neither shows up
 * in a golden-file test, because the file that is missing has no golden.
 *
 * <p>So this drives the real builder off the table rather than off a list written here: for every
 * entry with a parent, activating that parent alone must produce content under the child's key,
 * and a build without it must not. It is a fan-out guard, and the thing it guards is that the
 * fan-out has exactly one source.
 */
class ImplicitActivationFanOutTest {

    /**
     * One {@code @AILocked} class, which is enough for every implicit child: the Codex sidecars
     * render the whole model and the three safety files render the safety buckets, and
     * {@code @AILocked} is in both.
     */
    private static AnnotationCollector collectorWithOneLockedClass() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment round = mock(RoundEnvironment.class);

        Element locked = mock(Element.class);
        when(locked.toString()).thenReturn("com.example.Locked");
        when(locked.getKind()).thenReturn(ElementKind.CLASS);
        Name simpleName = mock(Name.class);
        when(simpleName.toString()).thenReturn("Locked");
        when(locked.getSimpleName()).thenReturn(simpleName);

        AILocked annotation = mock(AILocked.class);
        when(annotation.reason()).thenReturn("the partner contract that breaks");
        when(locked.getAnnotation(AILocked.class)).thenReturn(annotation);
        doReturn(Set.of(locked)).when(round).getElementsAnnotatedWith(AILocked.class);

        collector.collect(round);
        return collector;
    }

    private static GuardrailContentBuilder.Result buildWith(Set<String> activeServices) {
        return new GuardrailContentBuilder(
            collectorWithOneLockedClass(), activeServices, "Test", "").build();
    }

    /** The entries that no opt-in file activates, read from the table rather than listed here. */
    private static List<PlatformDescriptor> implicitChildren() {
        List<PlatformDescriptor> children = new ArrayList<>();
        for (PlatformDescriptor descriptor : PlatformDescriptors.ALL) {
            if (descriptor.implicitParent() != null) {
                children.add(descriptor);
            }
        }
        assertFalse(children.isEmpty(),
            "the table declares no implicit activations at all, so this test proves nothing");
        return children;
    }

    @Test
    @DisplayName("activating the parent renders the child, for every parent the table names")
    void everyDeclaredParentActivatesItsChild() {
        List<String> missing = new ArrayList<>();
        for (PlatformDescriptor child : implicitChildren()) {
            String parent = child.implicitParent();
            GuardrailContentBuilder.Result result = buildWith(Set.of(parent));
            if (result.contentByService.get(child.serviceKey()) == null) {
                missing.add(child.serviceKey() + " (parent " + parent + ")");
            }
        }
        assertEquals(List.of(), missing,
            "the table says these are activated by their parent and the builder did not render"
                + " them; an implicit child nobody renders is a file the user cannot ask for"
                + " either, because it is not an opt-in key");
    }

    @Test
    @DisplayName("without its parent, no child is rendered")
    void noChildIsRenderedWithoutItsParent() {
        GuardrailContentBuilder.Result result = buildWith(Set.of("claude"));
        List<String> unexpected = new ArrayList<>();
        for (PlatformDescriptor child : implicitChildren()) {
            if (result.contentByService.get(child.serviceKey()) != null) {
                unexpected.add(child.serviceKey());
            }
        }
        assertEquals(List.of(), unexpected,
            "invariant 1: file presence is the only opt-in, and these files' presence is their"
                + " parent's activation, not this build's");
    }

    @Test
    @DisplayName("a parent activates its own children and nobody else's")
    void aParentActivatesOnlyItsOwnChildren() {
        for (PlatformDescriptor child : implicitChildren()) {
            String parent = child.implicitParent();
            GuardrailContentBuilder.Result result = buildWith(Set.of(parent));
            for (PlatformDescriptor other : implicitChildren()) {
                if (other.implicitParent().equals(parent)) {
                    continue;
                }
                assertTrue(result.contentByService.get(other.serviceKey()) == null,
                    "activating " + parent + " rendered " + other.serviceKey()
                        + ", which the table says belongs to " + other.implicitParent());
            }
        }
    }

    /**
     * The condition that makes one loop able to render all five.
     *
     * <p>Before #830 the two Codex sidecars were handed {@code views.of(key)} and the three safety
     * files were handed the whole model. The loop hands everyone {@code views.of(key)}, so it is
     * behaviour-preserving exactly while the router is the identity for the three that were not
     * asking it, which it is: {@code routesTestGuardrails} answers false for every {@code *_safety}
     * key, and for a good reason rather than by accident. A safety file carries the six
     * always-loaded buckets, and those are precisely the guardrails that never move to
     * {@code TESTING.md}.
     *
     * <p>The Codex pair is deliberately not asserted either way. {@code codex_rules} does route,
     * and did before this change too, so the loop preserves that as it stands.
     */
    @Test
    @DisplayName("no safety file routes its test guardrails, which is what lets one loop render all five")
    void noSafetyChildRoutesItsTestGuardrails() {
        List<String> routed = new ArrayList<>();
        List<String> safety = new ArrayList<>();
        for (PlatformDescriptor child : implicitChildren()) {
            if (!child.serviceKey().endsWith("_safety")) {
                continue;
            }
            safety.add(child.serviceKey());
            if (ServiceRegistry.routesTestGuardrails(child.serviceKey())) {
                routed.add(child.serviceKey());
            }
        }
        assertFalse(safety.isEmpty(), "no safety file among the implicit children; nothing checked");
        assertEquals(List.of(), routed,
            "these were handed the whole model directly until #830 and are handed views.of(key)"
                + " now; the two are the same thing only while the router leaves them alone. A"
                + " safety file that routed would move the always-loaded tier into TESTING.md,"
                + " which is the one thing that tier must never do. If one should route, decide it"
                + " in ServiceRoutingContractTest, where every key is decided by hand, and change"
                + " this expectation deliberately rather than deleting it");
    }
}
