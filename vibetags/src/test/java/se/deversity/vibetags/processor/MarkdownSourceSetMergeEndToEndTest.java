package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module's main and test source sets render one Markdown document per aggregate, not two stacked
 * copies (issue #841).
 *
 * <p>#839 fixed this for {@code CLAUDE.md} only. Every other prose aggregate still repeated its
 * generated header and each section heading and intro once per source set: on this repository
 * that was about 5 KB of {@code AGENTS.md} and 4 KB of {@code GEMINI.md}, both loaded on every
 * session by the tools that read them.
 *
 * <p>The files are enumerated from {@link ServiceRegistry}, not listed here, so a renderer added
 * later without declaring the merge fails this test rather than quietly stacking again. Two
 * checks per file: no {@code #} or {@code ##} heading appears twice inside the generated region,
 * and wherever the main round's safety entries appear the test round's appear too, so the merge
 * cannot pass by dropping a source set.
 */
@Tag("e2e")
class MarkdownSourceSetMergeEndToEndTest {

    @TempDir
    Path root;

    private static final String MAIN_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AICore;
        import se.deversity.vibetags.annotations.AILocked;
        import se.deversity.vibetags.annotations.AISecure;

        @AILocked(reason = "main lock reason")
        @AICore(sensitivity = "high", note = "main core note")
        @AIContext(focus = "main focus", avoids = "main avoids")
        @AISecure(aspect = "main secure aspect")
        public class IrNode {
        }
        """;

    private static final String TEST_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AILocked;
        import se.deversity.vibetags.annotations.AISecure;

        @AILocked(reason = "test lock reason")
        @AIContext(focus = "test focus", avoids = "test avoids")
        @AISecure(aspect = "test secure aspect")
        public class IrNodeTest {
        }
        """;

    @ParameterizedTest(name = "TESTING.md opted in: {0}")
    @ValueSource(booleans = {false, true})
    void everyMarkdownAggregateRendersEachHeadingOnce(boolean testingMd) throws IOException {
        // A build file makes the root a module, which is what gives each source set its own body.
        // Without it the two compiles do not stack and this test passes without testing anything.
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Map<Path, String[]> files = optInEveryMarkedAggregate(testingMd);
        assertTrue(files.size() > 10, "precondition: the registry should yield the prose aggregates: " + files.keySet());

        for (String[] set : new String[][]{
                {"main", "com.example.core.IrNode", MAIN_SOURCE},
                {"test", "com.example.core.IrNodeTest", TEST_SOURCE}}) {
            ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
            harness.writeSourceFile("src/" + set[0] + "/java/" + set[1].replace('.', '/') + ".java", set[2]);
            harness.compile();
        }

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<Path, String[]> e : files.entrySet()) {
            String region = region(Files.readString(e.getKey(), StandardCharsets.UTF_8), e.getValue());
            if (region == null || region.isBlank()) {
                continue;
            }
            checked++;
            String name = root.relativize(e.getKey()).toString().replace('\\', '/');
            Map<String, Integer> headings = new LinkedHashMap<>();
            for (String line : region.split("\n")) {
                if (line.startsWith("# ") || line.startsWith("## ")) {
                    headings.merge(line.strip(), 1, Integer::sum);
                }
            }
            headings.forEach((heading, n) -> {
                if (n > 1) {
                    failures.add(name + ": \"" + heading + "\" x" + n);
                }
            });
            for (String[] pair : new String[][]{
                    {"main lock reason", "test lock reason"},
                    {"main secure aspect", "test secure aspect"}}) {
                if (region.contains(pair[0]) && !region.contains(pair[1])) {
                    failures.add(name + ": has \"" + pair[0] + "\" but lost \"" + pair[1] + "\"");
                }
            }
        }
        assertTrue(checked > 10, "precondition: most aggregates should have been written, got " + checked);
        assertFalse(!failures.isEmpty(),
            "a source set's scaffold is repeated or its entries lost in " + failures.size()
                + " place(s). A prose renderer stacks one body per source set unless it declares "
                + "PlatformRenderer.sourceSetMerge():\n  " + String.join("\n  ", failures));
    }

    /**
     * Every marker-based aggregate whose source sets are joined by concatenation today, created
     * holding its marker pair so that {@code AGENTS.md} is written alongside the others
     * (invariant 4). YAML files, whole-file merges, directories and ignore files have their own
     * join and are not what this checks.
     */
    private Map<Path, String[]> optInEveryMarkedAggregate(boolean testingMd) throws IOException {
        Map<Path, String[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : ServiceRegistry.buildServiceFileMap(root).entrySet()) {
            String key = e.getKey();
            Path file = e.getValue();
            if (ServiceRegistry.writesDirectory(key) || ServiceRegistry.isIgnoreService(key)
                    || PlatformRendererRegistry.mergeShapeFor(key) != null
                    || PlatformRendererRegistry.wholeFileMergeFor(key) != null
                    || ("testing".equals(key) && !testingMd)
                    || file.getFileName() == null) {
                continue;
            }
            String[] markers = GuardrailFileWriter.getMarkersFor(file.getFileName().toString());
            // .clinerules is both a single-file opt-in and the directory .clinerules/+vibetags-safety.md
            // lives in; the two are exclusive, so whichever comes second is left out.
            if (markers == null || Files.exists(file)
                    || Files.isRegularFile(file.getParent())) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, markers[0] + "\n" + markers[1] + "\n", StandardCharsets.UTF_8);
            files.put(file, markers);
        }
        return files;
    }

    private static String region(String text, String[] markers) {
        String normalized = text.replace("\r\n", "\n");
        int start = normalized.indexOf(markers[0]);
        int end = normalized.indexOf(markers[1], start + 1);
        return start < 0 || end < 0 ? null : normalized.substring(start + markers[0].length(), end);
    }
}
