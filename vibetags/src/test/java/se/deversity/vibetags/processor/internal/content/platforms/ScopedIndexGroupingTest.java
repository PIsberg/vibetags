package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.ClaudeSectionMerge;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoped-rules index names each shared prefix once (issue #839).
 *
 * <p>The index loads on every session. Written one element per line, every entry repeated its
 * full package: on this repository 18 entries spent most of their bytes on
 * {@code se.deversity.vibetags.processor.} and {@code .internal.}. Grouping by prefix drops that
 * repetition and loses nothing, because {@code prefix + "." + name} is the element again. The
 * alternatives #847 weighed fail here: abbreviated segments cannot be grepped or mapped to a file,
 * and one base hoisted over the whole index differs per source set, so the main and test rounds'
 * entries could not be merged into one section without rebasing every line.
 */
class ScopedIndexGroupingTest {

    private static final Pattern XML_GROUP = Pattern.compile("    <elements in=\"([^\"]+)\">([^<]+)</elements>");
    private static final Pattern XML_SINGLE = Pattern.compile("    <element path=\"([^\"]+)\"/>");
    private static final Pattern MD_GROUP = Pattern.compile("- `([^`]+)`: (.+)");

    @Test
    void xmlIndexNamesEachPrefixOnce() {
        String index = xmlIndex(cls("com.example.a.Alpha"), cls("com.example.a.Beta"), cls("com.example.b.Gamma"));

        assertTrue(index.contains("    <elements in=\"com.example.a\">Alpha, Beta</elements>\n"), index);
        assertTrue(index.contains("    <elements in=\"com.example.b\">Gamma</elements>\n"), index);
        assertEquals(1, index.split("com\\.example\\.a", -1).length - 1,
            "the shared prefix is written once, not once per element:\n" + index);
        assertTrue(index.contains("in=\"a.b\" listing C, D means a.b.C and a.b.D"),
            "the note says how to read a group back into a path:\n" + index);
    }

    @Test
    void xmlIndexIsLossless() {
        // A package owner and a nested type join groups too: the prefix is whatever precedes the
        // simple name, so a nested type's prefix is its enclosing type, which is still exact.
        TaggedElement[] owners = {
            cls("com.example.a.Alpha"),
            cls("com.example.a.Beta"),
            pkg("com.example.a.sub"),
            nested("com.example.a.Outer.Inner", "Inner"),
            cls("Toplevel"),
        };
        String index = xmlIndex(owners);

        assertEquals(qualifiedNames(owners), expandXml(index),
            "every element must be recoverable from the index, and nothing else:\n" + index);
        assertTrue(index.contains("    <element path=\"Toplevel\"/>\n"),
            "an element with no prefix keeps its own line:\n" + index);
    }

    @Test
    void markdownIndexNamesEachPrefixOnce() {
        TaggedElement[] owners = {cls("com.example.a.Alpha"), cls("com.example.a.Beta"), cls("com.example.b.Gamma")};
        StringBuilder sb = new StringBuilder();
        GranularIndexSection.appendMarkdownIndex(sb, Platform.CURSOR, context("cursor_granular", owners));
        String index = sb.toString();

        assertTrue(index.contains("- `com.example.a`: `Alpha`, `Beta`\n"), index);
        assertTrue(index.contains("- `com.example.b`: `Gamma`\n"), index);
        assertEquals(qualifiedNames(owners), expandMarkdown(index), index);
    }

    @Test
    void mainAndTestSourceSetsMergeWithoutLosingAnElement() {
        // The main and test rounds render one index each, and ClaudeSectionMerge joins them into
        // one section. Both name com.example.a with different members; both lines must survive.
        String main = body(xmlIndex(cls("com.example.a.Alpha"), cls("com.example.b.Gamma")));
        String test = body(xmlIndex(cls("com.example.a.AlphaTest")));

        String merged = ClaudeSectionMerge.merge(List.of(main, test));

        assertNotNull(merged, "the merge declined the grouped index, so the block would stack again");
        assertEquals(Set.of("com.example.a.Alpha", "com.example.b.Gamma", "com.example.a.AlphaTest"),
            expandXml(merged), merged);
        // The opening line, indented: the rule sentence after the section names <scoped_rules> too.
        assertEquals(merged.indexOf("  <scoped_rules>\n"), merged.lastIndexOf("  <scoped_rules>\n"),
            "one section, not two:\n" + merged);
    }

    // -----------------------------------------------------------------------

    private static String xmlIndex(TaggedElement... owners) {
        StringBuilder sb = new StringBuilder();
        GranularIndexSection.appendXmlIndex(sb, Platform.CLAUDE, context("claude_granular", owners));
        return sb.toString();
    }

    private static String body(String index) {
        return "<project_guardrails>\n" + index + "</project_guardrails>\n";
    }

    private static RenderingContext context(String granularKey, TaggedElement... owners) {
        return new RenderingContext("P", "h", Set.of("claude", "cursor", granularKey), 1024,
            new LinkedHashSet<>(Arrays.asList(owners)));
    }

    private static Set<String> qualifiedNames(TaggedElement... owners) {
        Set<String> names = new TreeSet<>();
        for (TaggedElement owner : owners) {
            names.add(owner.qualifiedName());
        }
        return names;
    }

    private static Set<String> expandXml(String text) {
        Set<String> names = new TreeSet<>();
        for (String line : text.split("\n")) {
            Matcher group = XML_GROUP.matcher(line);
            Matcher single = XML_SINGLE.matcher(line);
            if (group.matches()) {
                for (String member : group.group(2).split(", ")) {
                    names.add(group.group(1) + "." + member);
                }
            } else if (single.matches()) {
                names.add(single.group(1));
            }
        }
        return names;
    }

    private static Set<String> expandMarkdown(String text) {
        Set<String> names = new TreeSet<>();
        for (String line : text.split("\n")) {
            Matcher group = MD_GROUP.matcher(line);
            if (group.matches()) {
                for (String member : group.group(2).split(", ")) {
                    names.add(group.group(1) + "." + member.replace("`", ""));
                }
            }
        }
        return names;
    }

    private static TaggedElement cls(String qualifiedName) {
        return nested(qualifiedName, qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
    }

    private static TaggedElement nested(String qualifiedName, String simpleName) {
        return TaggedElement.builder(qualifiedName)
            .names(qualifiedName, simpleName, simpleName, qualifiedName.replaceAll("[^A-Za-z0-9-]", "-"))
            .kind(ElementTag.CLASS)
            .build();
    }

    private static TaggedElement pkg(String qualifiedName) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        return TaggedElement.builder(qualifiedName)
            .names(qualifiedName, simple, simple, qualifiedName.replaceAll("[^A-Za-z0-9-]", "-"))
            .kind(ElementTag.PACKAGE)
            .build();
    }
}
