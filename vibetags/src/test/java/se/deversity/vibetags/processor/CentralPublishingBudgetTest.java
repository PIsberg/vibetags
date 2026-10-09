package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every file a release puts on Maven Central counts against the organization's monthly allowance
 * of 1,167 files, and an organization that stays over it is rate limited (#863). Two plugin
 * defaults decided most of that count without anyone choosing it: the publishing plugin sends
 * SHA-256 and SHA-512 beside the required MD5 and SHA-1, and CycloneDX's {@code all} writes the
 * SBOM twice. Together they made a release 150 files instead of 84. Nothing else fails when either
 * comes back; the first sign would be Central's warning on a publish that already happened.
 *
 * <p>The organization's monthly allowance of 7 <em>releases</em> binds first, and Central counts
 * each published deployment as one. Deployed one module at a time, a vibetags version cost 5; the
 * release reactor in {@code .github/central-release} makes it 1.
 */
class CentralPublishingBudgetTest {

    /** A directory {@code publish.yml} hands to {@code deploy-to-central.sh}. */
    private static final Pattern DEPLOYED_MODULE = Pattern.compile(
        "^\\s*bash \\.github/scripts/deploy-to-central\\.sh\\s+(\\S+)", Pattern.MULTILINE);

    /** The aggregator {@code publish.yml} deploys, so that every module goes up as one deployment. */
    private static final String RELEASE_REACTOR = ".github/central-release";

    /** Modules that inherit from vibetags-parent but are never published, each with the reason. */
    private static final Map<String, String> NOT_PUBLISHED = Map.of(
        "load-tests", "the benchmark harness; its artifact is a workbench, never released");

    /**
     * One {@code deploy-to-central.sh} call, on the release reactor. Central counts each published
     * deployment against the organization's 7 releases a month; October 2026's publish made five
     * calls, one per module, and took the org from 1 to 6 (#863). A second call is a second release.
     */
    @Test
    void aReleaseIsOneCentralDeployment() throws IOException {
        assertEquals(List.of(RELEASE_REACTOR), deployCalls(),
            "publish.yml must deploy exactly once, on " + RELEASE_REACTOR + ". Every further "
                + "deploy-to-central.sh call is another release on Central's monthly count (#863).");
    }

    /**
     * The release reactor lists every module that is published, and nothing else. A module missing
     * from it is a module a release silently stops publishing; derived from the tree, so a new
     * module fails here until it is either added or named in {@link #NOT_PUBLISHED}.
     */
    @Test
    void theReleaseReactorBuildsEveryPublishedModule() {
        Set<String> expected = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(repoRoot())) {
            dirs.filter(dir -> Files.isRegularFile(dir.resolve("pom.xml")))
                .filter(dir -> inheritsTheParent(dir.resolve("pom.xml")))
                .map(dir -> String.valueOf(dir.getFileName()))
                .filter(name -> !NOT_PUBLISHED.containsKey(name))
                .forEach(expected::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(expected.size() >= 5, "found only " + expected + " inheriting vibetags-parent; "
            + "the derivation is broken and this test would check almost nothing");
        assertEquals(expected, new TreeSet<>(publishedModules()),
            RELEASE_REACTOR + "/pom.xml must list every module that inherits vibetags-parent and is "
                + "published, and only those.");
    }

    /**
     * The reactor is scaffolding, not an artifact. If it inherited the central-publish profile, or
     * the default deploy plugin ran on it, Central would receive a pom nobody can use, under the
     * project's group, in every release.
     */
    @Test
    void theReleaseReactorIsNotItselfPublished() {
        Document reactor = pom(repoRoot().resolve(RELEASE_REACTOR).resolve("pom.xml"));
        Element project = reactor.getDocumentElement();
        assertEquals(0, project.getElementsByTagName("parent").getLength(),
            RELEASE_REACTOR + "/pom.xml must not inherit from anything: a parent with the "
                + "central-publish profile would add the reactor itself to the bundle.");
        assertEquals("pom", childText(project, "packaging"));
        assertEquals("true", childText(firstChild(project, "properties"), "maven.deploy.skip"),
            RELEASE_REACTOR + "/pom.xml must set maven.deploy.skip, or the default deploy plugin "
                + "tries to deploy the reactor pom and fails the release.");
    }

    @Test
    void theCentralProfileSendsOnlyTheRequiredChecksums() {
        Element plugin = centralPublishingPlugin(pom(repoRoot().resolve("vibetags-parent/pom.xml")));
        assertEquals("required", childText(firstChild(plugin, "configuration"), "checksums"),
            "the central-publish profile must send MD5 and SHA-1 only. Central does not require "
                + "SHA-256 or SHA-512, and the plugin's default of all four makes every published file "
                + "6 files on Central instead of 4 (#863)");
    }

    @Test
    void everyPublishedModuleWritesItsSbomInOneFormat() {
        List<String> modules = publishedModules();
        assertFalse(modules.isEmpty(), "the release reactor lists no module, so this test would "
            + "check nothing");
        int withSbom = 0;
        for (String module : modules) {
            Element cyclonedx = plugin(pom(repoRoot().resolve(module).resolve("pom.xml")), "cyclonedx-maven-plugin");
            if (cyclonedx == null) {
                continue;
            }
            withSbom++;
            assertEquals("json", childText(firstChild(cyclonedx, "configuration"), "outputFormat"),
                module + " must publish its SBOM as JSON only. CycloneDX's 'all' attaches an XML copy "
                    + "too, which is 4 more files on Central per artifact per release (#863)");
        }
        assertTrue(withSbom > 0, "no module publish.yml deploys generates an SBOM; if that is "
            + "deliberate, delete this check rather than leaving it vacuous");
    }

    private static List<String> deployCalls() throws IOException {
        String workflow = Files.readString(repoRoot().resolve(".github/workflows/publish.yml"), StandardCharsets.UTF_8);
        List<String> targets = new ArrayList<>();
        Matcher matcher = DEPLOYED_MODULE.matcher(workflow);
        while (matcher.find()) {
            targets.add(matcher.group(1));
        }
        return targets;
    }

    /** The module directories the release reactor builds, relative to the repository root. */
    private static List<String> publishedModules() {
        Path reactor = repoRoot().resolve(RELEASE_REACTOR);
        Path file = reactor.resolve("pom.xml");
        assertTrue(Files.isRegularFile(file), file + " is missing; publish.yml deploys through it");
        NodeList modules = pom(file).getElementsByTagName("module");
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < modules.getLength(); i++) {
            Path dir = reactor.resolve(modules.item(i).getTextContent().trim()).normalize();
            dirs.add(String.valueOf(repoRoot().relativize(dir)).replace('\\', '/'));
        }
        return dirs;
    }

    private static boolean inheritsTheParent(Path pomFile) {
        NodeList parents = pom(pomFile).getDocumentElement().getElementsByTagName("parent");
        return parents.getLength() > 0
            && "vibetags-parent".equals(childText((Element) parents.item(0), "artifactId"));
    }

    private static Element centralPublishingPlugin(Document pom) {
        NodeList profiles = pom.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if ("central-publish".equals(childText(profile, "id"))) {
                Element plugin = plugin(profile, "central-publishing-maven-plugin");
                if (plugin != null) {
                    return plugin;
                }
            }
        }
        throw new AssertionError("vibetags-parent/pom.xml has no central-publish profile with "
            + "central-publishing-maven-plugin");
    }

    /** The first {@code <plugin>} under {@code scope} whose artifactId is {@code artifactId}, or null. */
    private static Element plugin(Node scope, String artifactId) {
        NodeList plugins = scope instanceof Document document
            ? document.getElementsByTagName("plugin")
            : ((Element) scope).getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (artifactId.equals(childText(plugin, "artifactId"))) {
                return plugin;
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        throw new AssertionError("<" + parent.getTagName() + "> has no <" + name + ">");
    }

    private static String childText(Element parent, String name) {
        return firstChild(parent, name).getTextContent().trim();
    }

    private static Document pom(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            throw new AssertionError("could not parse " + file, e);
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
        throw new AssertionError("could not find the repository root above " + System.getProperty("user.dir"));
    }
}
