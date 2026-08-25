package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.annotations.AIThreadAffinityFormatter;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two small mappings that are kept in agreement by nothing but care.
 *
 * <p>{@code GranularIndexSection} answers three questions per platform from three hand-written
 * switches: does this platform have a governing granular directory, which directory is it, and what
 * suffix do its files carry. When an aggregate file collapses to a scoped-rules index it writes
 * pointers built from those answers, and a pointer to a file that was never written is worse than
 * no index at all: the agent is told the detail lives somewhere, follows the pointer, and finds
 * nothing. Adding a platform to one switch and not the others is the way that happens, so what is
 * checked here is that the switches agree with each other for every platform that exists.
 *
 * <p>{@code AIThreadAffinityFormatter} turns an enum constant into the sentence an agent reads. A
 * constant missing from that switch falls through to the enum's own name — {@code BACKGROUND_ONLY}
 * rendered as "Pinned to BACKGROUND_ONLY", which is not wrong so much as useless, and looks
 * deliberate enough that nobody files it.
 */
class ScopedIndexAndAffinityTest {

    // -----------------------------------------------------------------------
    // GranularIndexSection: the three switches must agree
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Platform.class)
    void aPlatformHasAScopedDirectoryExactlyWhenItHasAGoverningGranularKey(Platform platform) {
        String key = GranularIndexSection.governingGranularKey(platform);
        String dir = GranularIndexSection.scopedDir(platform);

        if (key == null) {
            assertNull(dir, platform + " has no governing granular key but claims a scoped "
                + "directory, so an index would point into a directory nothing writes");
        } else {
            assertNotNull(dir, platform + " governs " + key + " but has no scoped directory, so "
                + "its index pointers would be bare stems with no path");
            assertFalse(dir.endsWith("/"), "scoped directories carry no trailing slash: " + dir);
        }
    }

    @Test
    void everyGoverningKeyIsAGranularServiceKey() {
        List<String> wrong = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            String key = GranularIndexSection.governingGranularKey(platform);
            if (key != null && !key.endsWith("_granular")) {
                wrong.add(platform + " -> " + key);
            }
        }
        assertEquals(List.of(), wrong,
            "the key is looked up in the active-services set, where granular services are named "
                + "<service>_granular; anything else can never match and the index never appears");
    }

    @Test
    void distinctGranularKeysGetDistinctDirectories() {
        // Two platforms sharing a directory is fine (CLAUDE and CLAUDE_LOCAL do). Two *keys*
        // sharing one is not: the granular writer would put both services' files in one place and
        // each service's orphan sweep would delete the other's.
        List<String> dirs = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            String key = GranularIndexSection.governingGranularKey(platform);
            if (key == null || keys.contains(key)) {
                continue;
            }
            keys.add(key);
            String dir = GranularIndexSection.scopedDir(platform);
            assertFalse(dirs.contains(dir),
                key + " reuses the scoped directory " + dir + " of another granular service");
            dirs.add(dir);
        }
        assertFalse(keys.isEmpty(), "no platform declared a granular key, so nothing was checked");
    }

    @Test
    void claudeLocalFollowsClaudesGranularState() {
        // CLAUDE.local.md is read by the same tool as CLAUDE.md and mirrors its content, so it has
        // to collapse to an index under the same condition, not under one of its own.
        assertEquals(GranularIndexSection.governingGranularKey(Platform.CLAUDE),
            GranularIndexSection.governingGranularKey(Platform.CLAUDE_LOCAL));
        assertEquals(GranularIndexSection.scopedDir(Platform.CLAUDE),
            GranularIndexSection.scopedDir(Platform.CLAUDE_LOCAL));
    }

    // -----------------------------------------------------------------------
    // PlatformRendererRegistry: an unknown service key has no shape
    // -----------------------------------------------------------------------

    @Test
    void anUnknownServiceKeyDeclaresNoMergeShape() {
        // The multi-module merge only knows service keys, and not every key has a renderer:
        // root_index is an opt-in file and nothing else. This must answer "no shape", not throw.
        assertNull(PlatformRendererRegistry.mergeShapeFor("root_index"));
        assertNull(PlatformRendererRegistry.mergeShapeFor("no-such-service"));
        assertNull(PlatformRendererRegistry.wholeFileMergeFor("root_index"));
        assertNull(PlatformRendererRegistry.wholeFileMergeFor("no-such-service"));
    }

    // -----------------------------------------------------------------------
    // AIThreadAffinityFormatter: every constant gets its own sentence
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(AIThreadAffinity.Affinity.class)
    void everyAffinityConstantRendersAsProseNotAsItsEnumName(AIThreadAffinity.Affinity affinity) {
        StringBuilder sb = new StringBuilder();
        new AIThreadAffinityFormatter()
            .format(element(affinity, "", "", ""), sb, Platform.CURSOR);
        String rendered = sb.toString();

        assertTrue(rendered.contains("Pinned to"), rendered);
        assertFalse(rendered.contains(affinity.name()),
            affinity + " fell through to the enum's own name; an agent reading \"Pinned to "
                + affinity.name() + "\" learns nothing it could act on: " + rendered);
    }

    @Test
    void theFourAffinitiesAreDescribedDistinctly() {
        // Two constants rendering the same sentence would make the annotation's central
        // distinction — which thread, not whether to lock — invisible in the generated file.
        List<String> described = new ArrayList<>();
        for (AIThreadAffinity.Affinity affinity : AIThreadAffinity.Affinity.values()) {
            StringBuilder sb = new StringBuilder();
            new AIThreadAffinityFormatter()
                .format(element(affinity, "", "", ""), sb, Platform.CURSOR);
            assertFalse(described.contains(sb.toString()),
                affinity + " renders identically to an earlier constant: " + sb);
            described.add(sb.toString());
        }
        assertEquals(4, described.size());
    }

    @Test
    void aNamedAffinityUsesTheThreadNameWhenThereIsOne() {
        StringBuilder named = new StringBuilder();
        new AIThreadAffinityFormatter().format(
            element(AIThreadAffinity.Affinity.NAMED, "Swing EDT", "", ""), named, Platform.CURSOR);
        assertTrue(named.toString().contains("Swing EDT"), named.toString());

        StringBuilder unnamed = new StringBuilder();
        new AIThreadAffinityFormatter().format(
            element(AIThreadAffinity.Affinity.NAMED, "", "", ""), unnamed, Platform.CURSOR);
        assertTrue(unnamed.toString().contains("one specific named thread"),
            "NAMED with no name still has to say something specific: " + unnamed);
    }

    @Test
    void theSummaryAlwaysSaysThatLockingIsTheWrongFix() {
        // The whole point of the annotation: an agent told "make it thread-safe" adds a lock, and
        // the requirement is which thread runs the call. That sentence must survive every shape.
        for (AIThreadAffinity.Affinity affinity : AIThreadAffinity.Affinity.values()) {
            StringBuilder sb = new StringBuilder();
            new AIThreadAffinityFormatter().format(
                element(affinity, "worker", "the event queue", "silent corruption"),
                sb, Platform.CURSOR);
            assertTrue(sb.toString().contains("adding a lock is the wrong fix"), sb.toString());
            assertTrue(sb.toString().contains("Marshal via the event queue"), sb.toString());
            assertTrue(sb.toString().contains("silent corruption"), sb.toString());
        }
    }

    /** A tagged element carrying one {@code @AIThreadAffinity} with the given member values. */
    private static TaggedElement element(AIThreadAffinity.Affinity affinity, String thread,
                                         String marshalVia, String symptom) {
        AIThreadAffinity annotation = (AIThreadAffinity) Proxy.newProxyInstance(
            AIThreadAffinity.class.getClassLoader(),
            new Class<?>[]{AIThreadAffinity.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> AIThreadAffinity.class;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@AIThreadAffinity";
                case "value" -> affinity;
                case "thread" -> thread;
                case "marshalVia" -> marshalVia;
                case "symptomIfViolated" -> symptom;
                default -> defaultOf(method);
            });

        String fqn = "com.example.ui.Renderer";
        return TaggedElement.builder(fqn)
            .names(fqn, "Renderer", fqn, fqn)
            .kind(ElementTag.CLASS)
            .annotation(AIThreadAffinity.class, annotation)
            .build();
    }

    private static Object defaultOf(java.lang.reflect.Method method) {
        Object declared = method.getDefaultValue();
        if (declared != null) {
            return declared;
        }
        throw new IllegalStateException(
            "AIThreadAffinity." + method.getName() + " gained a member with no default; give the "
                + "fixture a value for it");
    }

}
