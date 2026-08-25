package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AISunset;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * How an annotation's members are flattened into the strings a published manifest carries.
 *
 * <p>{@code membersOf} is the boundary between a live annotation and a JSON document that a
 * <em>different</em> build, on a different machine, reads back out of a JAR. Whatever it drops here
 * is gone: the consuming build has no access to the annotation instance and cannot recover it. So
 * the questions are what gets dropped (a member left at its default, which would otherwise bloat
 * every manifest with values the reader already knows) and what survives intact (an array member,
 * a {@code Class} member, and the ordering that keeps two builds of the same source producing
 * byte-identical manifests).
 *
 * <p>{@link TransitiveManifestTest} covers the JSON document. This covers the flattening.
 */
class TransitiveManifestMembersTest {

    /**
     * An annotation instance answering each member from {@code values}, falling back to the
     * declared default. A {@link Proxy} rather than a real annotated element: the point is to vary
     * one member at a time, and only reflection sees the difference.
     */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A annotation(Class<A> type, Map<String, Object> values) {
        return (A) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@" + type.getName();
                default -> values.containsKey(method.getName())
                    ? values.get(method.getName())
                    : defaultOf(method);
            });
    }

    private static Object defaultOf(Method method) {
        Object declared = method.getDefaultValue();
        if (declared != null) {
            return declared;
        }
        throw new IllegalStateException(method.getName() + " has no default; give it a value");
    }

    @Test
    void aMemberLeftAtItsDefaultIsNotCarried() {
        // Every manifest would otherwise repeat the defaults of every rule it publishes, and the
        // reader already knows them: they are compiled into the annotation on its own classpath.
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AILocked.class, Map.of()));

        assertEquals(Map.of(), members,
            "an annotation written bare carries no members at all, was: " + members);
    }

    @Test
    void aMemberSetToSomethingOtherThanItsDefaultIsCarried() {
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AILocked.class, Map.of("reason", "settlement is regulator-audited")));

        assertEquals("settlement is regulator-audited", members.get("reason"));
    }

    @Test
    void aMemberSetToExactlyItsDefaultValueIsStillTreatedAsDefault() {
        // The comparison has to be on the value, not on "did the user write it": a source
        // annotation and a proxy answering the same value must produce the same manifest, or two
        // builds of the same code publish different bytes.
        Method reason = declaredMethod(AILocked.class, "reason");
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AILocked.class, Map.of("reason", reason.getDefaultValue())));

        assertFalse(members.containsKey("reason"), "was: " + members);
    }

    @Test
    void anArrayMemberIsJoinedWithCommas() {
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AIArchitecture.class, Map.of(
                "belongsTo", "domain",
                "cannotReference", new String[]{"com.example.infra", "com.example.web"})));

        assertEquals("com.example.infra, com.example.web", members.get("cannotReference"));
    }

    @Test
    void blankEntriesInAnArrayMemberAreDropped() {
        // A trailing comma in the source produces an empty entry; rendering it would publish a
        // rule naming a package called "".
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AIArchitecture.class, Map.of(
                "belongsTo", "domain",
                "cannotReference", new String[]{"com.example.infra", "  ", ""})));

        assertEquals("com.example.infra", members.get("cannotReference"));
    }

    @Test
    void anArrayMemberWholeAtItsEmptyDefaultIsNotCarried() {
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AIArchitecture.class, Map.of("belongsTo", "domain")));

        assertEquals(List.of("belongsTo"), List.copyOf(members.keySet()),
            "an unset String[] member must be dropped, not published as an empty string");
    }

    @Test
    void anArrayMemberHoldingOnlyBlanksRendersEmptyAndIsDropped() {
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AIArchitecture.class, Map.of(
                "belongsTo", "domain",
                "cannotReference", new String[]{" ", ""})));

        assertFalse(members.containsKey("cannotReference"),
            "a member that renders to nothing must not occupy a key, was: " + members);
    }

    @Test
    void aClassMemberIsCarriedByItsBinaryName() {
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AISunset.class, Map.of("replacement", java.util.List.class)));

        assertEquals("java.util.List", members.get("replacement"),
            "the consuming build resolves this name against its own classpath; a simple name or a "
                + "toString() form would not resolve");
    }

    @Test
    void membersAreOrderedByNameNotByDeclarationOrder() {
        // getDeclaredMethods() has no specified order, and a manifest whose key order varies
        // between JVMs is a manifest whose bytes vary between builds of identical source.
        Map<String, String> members = TransitiveManifest.membersOf(
            annotation(AIBannedApi.class, Map.of(
                "useInstead", "java.time.Instant",
                "reason", "Date is mutable",
                "forbidden", new String[]{"java.util.Date"})));

        List<String> keys = List.copyOf(members.keySet());
        assertEquals(keys.stream().sorted().toList(), keys,
            "keys must come out sorted, was: " + keys);
        assertEquals(List.of("forbidden", "reason", "useInstead"), keys,
            "declaration order is forbidden, useInstead, reason; the manifest must not follow it");
    }

    @Test
    void aMemberThatThrowsWhenReadIsSkippedRatherThanLosingTheWholeRule() {
        // A Class-valued member read off a live javax.lang.model mirror throws
        // MirroredTypeException. Losing that one attribute is a smaller failure than publishing
        // no rule for the element at all.
        AILocked hostile = (AILocked) Proxy.newProxyInstance(
            AILocked.class.getClassLoader(), new Class<?>[]{AILocked.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> AILocked.class;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@AILocked";
                default -> throw new IllegalStateException("member unavailable on a live mirror");
            });

        assertEquals(Map.of(), TransitiveManifest.membersOf(hostile),
            "an unreadable member must cost its own key, not the caller's rule");
    }

    private static Method declaredMethod(Class<? extends Annotation> type, String name) {
        try {
            return type.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + " has no member " + name, e);
        }
    }
}
