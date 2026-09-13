package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mvn test -Dtest=SomeTest} must run the named test in the surefire execution that owns
 * it, and only there (#686).
 *
 * <p>Surefire runs two executions of this module: {@code default-test}, which excludes
 * {@code *AsyncTest}, and {@code async-tests}, which runs only those with the async-test agent
 * attached. {@code -Dtest} overrides the includes and excludes of every execution, so before the
 * {@code named-test-overrides-tags} profile re-filtered each one, a named ordinary test also ran
 * in {@code async-tests}. A test that drives javac fails there with
 * {@code NoClassDefFoundError: se/deversity/asynctest/telemetry/TelemetryRegistry} while passing
 * in {@code default-test}: a red build for a passing test, following the command CLAUDE.md
 * documents. A named async test ran in {@code default-test} too, without the agent.
 *
 * <p>Maven cannot be run from here, so this test evaluates the profile's own configuration the
 * way the plugins do: the {@code build-helper:regex-properties} settings in declaration order
 * (a regex that does not match leaves the value as it was), and surefire's {@code %regex[]}
 * exclusion against the real test class paths. The Maven commands it stands in for, and their
 * output, are recorded in the pull request that closed #686.
 */
class NamedTestExecutionRoutingTest {

    private static final String PROFILE = "named-test-overrides-tags";
    private static final String DEFAULT_FAIL = "vibetags.test.default.failIfNoSpecifiedTests";
    private static final String ASYNC_FAIL = "vibetags.test.async.failIfNoSpecifiedTests";
    private static final String ASYNC_SUFFIX = "AsyncTest.class";

    /**
     * Whether each execution fails when {@code -Dtest} matches nothing in it. An execution that
     * cannot own any named entry must not fail for being empty, and an execution that should have
     * run something must still fail, or a typo in {@code -Dtest} would pass green.
     */
    @ParameterizedTest(name = "-Dtest={0} -> default-test {1}, async-tests {2}")
    @CsvSource(delimiter = '|', value = {
        "AnnotationDefinitionsTest | true | false",
        "NoSuchTest | true | false",
        "VibeTagsLoggerAsyncTest | false | true",
        "NoSuchAsyncTest | false | true",
        "VibeTagsLoggerAsyncTest#someMethod | false | true",
        "*AsyncTest | false | true",
        "AnnotationMirrorAnchorTest,VibeTagsLoggerAsyncTest | true | true",
        "'VibeTagsLoggerAsyncTest, WriteCacheAsyncTest' | false | true",
        "*Writer* | true | false",
        "'AnnotationMirrorAnchorTest, !WriteCacheAsyncTest' | true | false",
        "'VibeTagsLoggerAsyncTest, !NoSuch*' | false | true",
    })
    void eachExecutionInsistsOnAMatchOnlyForTheEntriesItOwns(
            String pattern, boolean defaultFails, boolean asyncFails) {
        Map<String, String> properties = evaluateRegexProperties(pattern);

        assertEquals(String.valueOf(defaultFails), properties.get(DEFAULT_FAIL),
            "default-test failIfNoSpecifiedTests for -Dtest=" + pattern);
        assertEquals(String.valueOf(asyncFails), properties.get(ASYNC_FAIL),
            "async-tests failIfNoSpecifiedTests for -Dtest=" + pattern);
    }

    @Test
    void eachExecutionReadsTheNamedPatternAndDropsTheOtherExecutionsTests() {
        Element surefire = plugin(profile(), "maven-surefire-plugin");

        Element defaultTest = configuration(execution(surefire, "default-test"));
        assertEquals("${test},!**/*AsyncTest", childText(defaultTest, "test"),
            "default-test must re-read -Dtest and exclude the async tests, or a named async test"
                + " runs there without the agent");
        assertEquals("${" + DEFAULT_FAIL + "}", childText(defaultTest, "failIfNoSpecifiedTests"));

        Element asyncTests = configuration(execution(surefire, "async-tests"));
        assertEquals("${" + ASYNC_FAIL + "}", childText(asyncTests, "failIfNoSpecifiedTests"));
        String test = childText(asyncTests, "test");
        Matcher exclusion = Pattern.compile("^\\$\\{test},!%regex\\[(.*)]$").matcher(test);
        assertTrue(exclusion.matches(),
            "async-tests must re-read -Dtest and exclude every non-async test, got " + test);
        Pattern excluded = Pattern.compile(exclusion.group(1));

        List<String> classes = testClassPaths();
        assertTrue(classes.stream().anyMatch(c -> c.endsWith(ASYNC_SUFFIX)),
            "found no *AsyncTest classes under vibetags/src/test/java");
        for (String testClass : classes) {
            boolean async = testClass.endsWith(ASYNC_SUFFIX);
            assertEquals(!async, excluded.matcher(testClass).matches(),
                async
                    ? "async-tests would exclude its own test " + testClass
                    : "async-tests would run " + testClass + " under the agent");
        }
    }

    /**
     * The profile's executions merge with the main build's only by id. If an id or the async
     * pattern drifts, the profile silently configures an execution that no longer exists.
     */
    @Test
    void theProfileTargetsTheExecutionsTheMainBuildDeclares() {
        Element build = firstChild(pom().getDocumentElement(), "build");
        Element surefire = plugin(build, "maven-surefire-plugin");
        assertEquals("**/*AsyncTest.java", childText(
            firstChild(configuration(execution(surefire, "default-test")), "excludes"), "exclude"));
        assertEquals("**/*AsyncTest.java", childText(
            firstChild(configuration(execution(surefire, "async-tests")), "includes"), "include"));

        Element activation = firstChild(profile(), "activation");
        assertEquals("test", childText(firstChild(activation, "property"), "name"),
            PROFILE + " must activate on -Dtest, the only time its routing applies");
    }

    // -----------------------------------------------------------------------

    /** Applies the profile's regex-properties settings in order, as build-helper does. */
    private static Map<String, String> evaluateRegexProperties(String test) {
        Map<String, String> properties = new HashMap<>();
        properties.put("test", test);
        NodeList settings = plugin(profile(), "build-helper-maven-plugin")
            .getElementsByTagName("regexPropertySetting");
        assertTrue(settings.getLength() > 0, PROFILE + " declares no regex-properties settings");
        for (int i = 0; i < settings.getLength(); i++) {
            Element setting = (Element) settings.item(i);
            String value = resolve(childText(setting, "value"), properties);
            Matcher matcher = Pattern.compile(childText(setting, "regex")).matcher(value);
            String result = matcher.find() ? matcher.replaceAll(childText(setting, "replacement")) : value;
            properties.put(childText(setting, "name"), result);
        }
        return properties;
    }

    private static String resolve(String expression, Map<String, String> properties) {
        Matcher reference = Pattern.compile("^\\$\\{([^}]+)}$").matcher(expression);
        assertTrue(reference.matches(), "expected a single property reference, got " + expression);
        String name = reference.group(1);
        assertTrue(properties.containsKey(name), name + " is read before any setting defines it");
        return properties.get(name);
    }

    private static List<String> testClassPaths() {
        Path sources = repoRoot().resolve("vibetags/src/test/java");
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".java"))
                .map(p -> sources.relativize(p).toString().replace('\\', '/').replaceAll("\\.java$", ".class"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Element profile() {
        NodeList profiles = pom().getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if (PROFILE.equals(childText(profile, "id"))) {
                return profile;
            }
        }
        throw new AssertionError("vibetags/pom.xml has no profile " + PROFILE);
    }

    private static Element plugin(Element scope, String artifactId) {
        NodeList plugins = scope.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (artifactId.equals(childText(plugin, "artifactId"))) {
                return plugin;
            }
        }
        throw new AssertionError("no " + artifactId + " under <" + scope.getTagName() + ">");
    }

    private static Element execution(Element plugin, String id) {
        NodeList executions = plugin.getElementsByTagName("execution");
        for (int i = 0; i < executions.getLength(); i++) {
            Element execution = (Element) executions.item(i);
            if (id.equals(childText(execution, "id"))) {
                return execution;
            }
        }
        throw new AssertionError("no execution " + id + " on " + childText(plugin, "artifactId"));
    }

    private static Element configuration(Element execution) {
        return firstChild(execution, "configuration");
    }

    private static Element firstChild(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        throw new AssertionError("<" + parent.getTagName() + "> has no <" + name + ">");
    }

    private static String childText(Element parent, String name) {
        return firstChild(parent, name).getTextContent().trim();
    }

    private static Document pom() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(repoRoot().resolve("vibetags/pom.xml").toFile());
        } catch (Exception e) {
            throw new AssertionError("could not parse vibetags/pom.xml", e);
        }
    }

    private static Path repoRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 4 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("vibetags-parent/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("could not locate vibetags-parent/pom.xml from "
            + System.getProperty("user.dir"));
    }
}
