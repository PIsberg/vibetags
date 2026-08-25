package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coverage gate is a ratchet, and the classes it deliberately does not reach are written down.
 *
 * <p>Issue #482 asked how to handle the processor's fault paths — the catch blocks that need a
 * filesystem which fails one specific write, or an executor task interrupted mid-flight. Reaching
 * them from JUnit means adding seams to production code purely so a test can fail them, on classes
 * {@code CLAUDE.md} marks high-sensitivity. The decision was to accept the level and document it,
 * which is only worth anything if both halves of that decision are enforced. This is the
 * enforcement.
 *
 * <p><b>The ratchet.</b> {@code codecov.yml} uses {@code target: auto}, so the gate asks "did this
 * pull request lose coverage" rather than "is coverage above a number somebody typed in 2026". The
 * fixed 90% it replaced had drifted into slack — measured coverage was several points above it, so
 * a change could shed those points and still pass green. Swapping the ratchet back for a fixed
 * floor is exactly the silent weakening this test exists to catch.
 *
 * <p><b>The documentation.</b> {@code docs/TESTS.md} names the six classes that hold most of the
 * uncovered lines, with what those lines are. A list of class names in prose rots the moment one is
 * renamed, and a rotted list reads as though the decision still holds when nobody has checked. So
 * every name in that table has to resolve to a source file.
 */
@DisplayName("The coverage gate blocks regressions, and the fault-path list has not rotted")
class CoverageGateTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** Matches a class name in the first column of the fault-path table: {@code | `ModuleSidecar` |}. */
    private static final Pattern TABLE_ROW =
        Pattern.compile("(?m)^\\|\\s*`([A-Z][A-Za-z0-9]*)`\\s*\\|\\s*\\d+\\s*\\|");

    @Test
    @DisplayName("codecov compares against the base commit rather than a fixed floor")
    void codecovProjectStatusIsARatchet() throws IOException {
        Path config = REPO_ROOT.resolve("codecov.yml");
        assertTrue(Files.isRegularFile(config), "codecov.yml not found at " + config);

        Map<String, Object> project = projectDefault(config);
        assertTrue(project != null && project.containsKey("target"),
            "codecov.yml declares no project status target, so nothing gates coverage at all");

        assertEquals("auto", String.valueOf(project.get("target")),
            "codecov.yml's project target is no longer `auto`. A fixed percentage becomes slack the "
                + "moment real coverage rises above it: the 90% this replaced allowed a change to "
                + "shed several points and still pass. If the ratchet is being dropped on purpose, "
                + "say why in docs/TESTS.md under 'Coverage and the fault paths' and change this "
                + "test in the same commit (#482).");

        Object threshold = project.get("threshold");
        assertTrue(threshold != null && percent(String.valueOf(threshold)) <= 1.0,
            "codecov.yml's project threshold is " + threshold + ". A ratchet with a wide threshold "
                + "is not a ratchet — keep it at 1% or tighter.");
    }

    @Test
    @DisplayName("every class in the documented fault-path table still exists")
    void documentedFaultPathClassesStillExist() throws IOException {
        Path doc = REPO_ROOT.resolve("docs/TESTS.md");
        assertTrue(Files.isRegularFile(doc), "docs/TESTS.md not found at " + doc);
        String text = Files.readString(doc, StandardCharsets.UTF_8);

        int section = text.indexOf("## Coverage and the fault paths");
        assertTrue(section >= 0,
            "docs/TESTS.md no longer has a 'Coverage and the fault paths' section. That section is "
                + "the whole of the #482 decision — without it, the uncovered fault paths look "
                + "like an oversight rather than a choice somebody made and wrote down.");

        List<String> named = new ArrayList<>();
        Matcher matcher = TABLE_ROW.matcher(text.substring(section));
        while (matcher.find()) {
            named.add(matcher.group(1));
        }
        assertTrue(named.size() >= 5,
            "the fault-path table lists only " + named + ", so this check has stopped checking "
                + "anything meaningful — it was written against six classes");

        List<String> missing = new ArrayList<>();
        for (String className : named) {
            if (!sourceExists(className)) {
                missing.add(className);
            }
        }
        assertEquals(List.of(), missing,
            "docs/TESTS.md names these classes as fault-path-heavy but no source file matches them. "
                + "A renamed or deleted class leaves the table describing code that is not there, "
                + "which reads as though the coverage decision still holds when nobody has checked.");
    }

    /** True when exactly one {@code <className>.java} exists anywhere under the processor sources. */
    private static boolean sourceExists(String className) throws IOException {
        Path main = REPO_ROOT.resolve("vibetags/src/main/java");
        try (var paths = Files.walk(main)) {
            return paths.anyMatch(p -> p.getFileName().toString().equals(className + ".java"));
        }
    }

    /** {@code coverage.status.project.default} from the parsed YAML, or null when absent. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> projectDefault(Path config) throws IOException {
        try (InputStream in = Files.newInputStream(config)) {
            Map<String, Object> root = new Yaml().load(in);
            Object coverage = root == null ? null : root.get("coverage");
            if (!(coverage instanceof Map<?, ?> coverageMap)) {
                return null;
            }
            Object status = coverageMap.get("status");
            if (!(status instanceof Map<?, ?> statusMap)) {
                return null;
            }
            Object project = statusMap.get("project");
            if (!(project instanceof Map<?, ?> projectMap)) {
                return null;
            }
            Object byDefault = projectMap.get("default");
            return byDefault instanceof Map ? (Map<String, Object>) byDefault : null;
        }
    }

    /** {@code "1%"} and {@code "1"} both read as 1.0. */
    private static double percent(String raw) {
        return Double.parseDouble(raw.endsWith("%") ? raw.substring(0, raw.length() - 1) : raw);
    }
}
