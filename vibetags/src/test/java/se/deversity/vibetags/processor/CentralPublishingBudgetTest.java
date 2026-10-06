package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
class CentralPublishingBudgetTest {

    /** A module directory {@code publish.yml} hands to {@code deploy-to-central.sh}. */
    private static final Pattern DEPLOYED_MODULE = Pattern.compile(
        "^\\s*bash \\.github/scripts/deploy-to-central\\.sh\\s+(\\S+)", Pattern.MULTILINE);

    @Test
    void theCentralProfileSendsOnlyTheRequiredChecksums() {
        Element plugin = centralPublishingPlugin(pom(repoRoot().resolve("vibetags-parent/pom.xml")));
        assertEquals("required", childText(firstChild(plugin, "configuration"), "checksums"),
            "the central-publish profile must send MD5 and SHA-1 only. Central does not require "
                + "SHA-256 or SHA-512, and the plugin's default of all four makes every published file "
                + "6 files on Central instead of 4 (#863)");
    }

    @Test
    void everyPublishedModuleWritesItsSbomInOneFormat() throws IOException {
        List<String> modules = deployedModules();
        assertFalse(modules.isEmpty(), "found no deploy-to-central.sh call in publish.yml; the "
            + "pattern no longer matches how it deploys, so this test would check nothing");
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

    private static List<String> deployedModules() throws IOException {
        String workflow = Files.readString(repoRoot().resolve(".github/workflows/publish.yml"), StandardCharsets.UTF_8);
        List<String> modules = new ArrayList<>();
        Matcher matcher = DEPLOYED_MODULE.matcher(workflow);
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }
        return modules;
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
