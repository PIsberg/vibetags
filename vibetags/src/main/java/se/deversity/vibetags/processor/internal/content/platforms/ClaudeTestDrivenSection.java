package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Renders the body of the {@code CLAUDE.md} {@code <test_driven_requirements>} section,
 * coalescing {@code @AITestDriven} elements that share identical guardrail values into a
 * single {@code <test_driven_default>} block plus an {@code <applies-to>} member list
 * (issue #283). Elements whose values diverge — or a set too small to benefit — keep their
 * per-element {@code <element>} stanza, rendered by {@link AITestDrivenFormatter} exactly as
 * before.
 *
 * <p>The section wrapper tags and the trailing {@code <rule>} stay in {@link ClaudeRenderer};
 * this class only produces the inner body. Grouping and member order follow the collector's
 * insertion order, so the output is deterministic across recompiles.
 */
final class ClaudeTestDrivenSection {

    /** Placeholder for the Maven mirror-convention test path, expanded per member by the reader. */
    private static final String TEMPLATE_LOCATION = "src/test/java/{path}Test.java";

    /** Minimum members before a group collapses into a {@code <test_driven_default>} block. */
    private static final int MIN_COALESCE = 2;

    private ClaudeTestDrivenSection() {
    }

    /** How a group's {@code testLocation} is represented in the coalesced default block. */
    private enum LocationKind { EMPTY, TEMPLATE, LITERAL }

    /** The value tuple that makes two {@code @AITestDriven} stanzas identical for CLAUDE output. */
    private record Signature(int coverageGoal, String frameworks, String mockPolicy,
                             LocationKind locationKind, String locationLiteral) {
    }

    /**
     * Appends the section body — the coalesced default block (when a group of {@value #MIN_COALESCE}+
     * value-identical elements exists) followed by individual stanzas for every remaining element —
     * to {@code sb}.
     *
     * @param elements the {@code @AITestDriven} elements, in collector insertion order
     * @param sb       the destination buffer
     */
    static void render(Collection<Element> elements, StringBuilder sb) {
        Map<Signature, List<Element>> groups = new LinkedHashMap<>();
        for (Element e : elements) {
            AITestDriven td = e.getAnnotation(AITestDriven.class);
            if (td == null) {
                continue;
            }
            groups.computeIfAbsent(signature(e, td), k -> new ArrayList<>()).add(e);
        }

        // Largest group becomes the default; ties resolve to the first-seen group (LinkedHashMap
        // iteration order) via the strict > comparison, keeping the choice deterministic.
        Signature defaultSig = null;
        int best = 0;
        for (Map.Entry<Signature, List<Element>> entry : groups.entrySet()) {
            if (entry.getValue().size() > best) {
                best = entry.getValue().size();
                defaultSig = entry.getKey();
            }
        }

        boolean coalesce = best >= MIN_COALESCE;
        if (coalesce) {
            appendDefaultBlock(defaultSig, groups.get(defaultSig), sb);
        }

        for (Element e : elements) {
            AITestDriven td = e.getAnnotation(AITestDriven.class);
            if (td == null) {
                continue;
            }
            if (coalesce && signature(e, td).equals(defaultSig)) {
                continue;
            }
            FormatterRegistry.testDriven().format(e, sb, Platform.CLAUDE);
        }
    }

    private static void appendDefaultBlock(Signature sig, List<Element> members, StringBuilder sb) {
        sb.append("    <test_driven_default coverage_goal=\"").append(sig.coverageGoal())
          .append("\" frameworks=\"").append(Escape.xml(sig.frameworks())).append('"');

        if (sig.locationKind() == LocationKind.TEMPLATE) {
            sb.append(" test_location=\"").append(TEMPLATE_LOCATION).append('"');
        } else if (sig.locationKind() == LocationKind.LITERAL) {
            sb.append(" test_location=\"").append(Escape.xml(sig.locationLiteral())).append('"');
        }
        if (!sig.mockPolicy().isEmpty()) {
            sb.append(" mock_policy=\"").append(Escape.xml(sig.mockPolicy())).append('"');
        }
        sb.append(">\n");

        sb.append("      <applies-to>\n");
        for (int i = 0; i < members.size(); i++) {
            sb.append("        ").append(Escape.xml(ElementNaming.elementPath(members.get(i))));
            if (i < members.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("      </applies-to>\n");
        sb.append("    </test_driven_default>\n");
    }

    private static Signature signature(Element e, AITestDriven td) {
        String loc = td.testLocation();
        LocationKind kind;
        String literal = "";
        if (loc.isEmpty()) {
            kind = LocationKind.EMPTY;
        } else if (isConventionPath(e, loc)) {
            kind = LocationKind.TEMPLATE;
        } else {
            kind = LocationKind.LITERAL;
            literal = loc;
        }
        return new Signature(td.coverageGoal(), frameworks(td), td.mockPolicy(), kind, literal);
    }

    /** Frameworks joined with {@code ", "}, matching {@link AITestDrivenFormatter}. */
    private static String frameworks(AITestDriven td) {
        StringBuilder f = new StringBuilder();
        for (AITestDriven.Framework fw : td.framework()) {
            if (f.length() > 0) {
                f.append(", ");
            }
            f.append(fw.name());
        }
        return f.toString();
    }

    /** True when {@code loc} is the Maven mirror-convention test path for a TYPE element. */
    private static boolean isConventionPath(Element e, String loc) {
        ElementKind kind = e.getKind();
        if (!kind.isClass() && !kind.isInterface()) {
            return false;
        }
        String fqnPath = ElementNaming.elementPath(e).replace('.', '/');
        return loc.equals("src/test/java/" + fqnPath + "Test.java");
    }
}
