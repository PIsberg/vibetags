package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manifest format: what a library publishes and what a consumer reads back. */
class TransitiveManifestTest {

    private static TransitiveRule rule(String pkg, String label, String... kv) {
        Map<String, String> members = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            members.put(kv[i], kv[i + 1]);
        }
        return new TransitiveRule("com.acme:core:1.0", pkg, label,
            TransitiveManifest.tierOf(label), members);
    }

    @Test
    void roundTripsAllFields() {
        List<TransitiveRule> original = List.of(
            rule("com.acme.api", "@AISecure", "aspect", "key material"),
            rule("com.acme.api", "@AIPerformance", "constraint", "no allocation"));

        List<TransitiveRule> parsed = TransitiveManifest.parse(
            TransitiveManifest.toJson("com.acme.api", "com.acme:core:1.0", original, "9.9.9"),
            "ignored");

        assertEquals(2, parsed.size());
        assertTrue(parsed.containsAll(original),
            "a rule must survive publication and consumption unchanged: " + parsed);
    }

    @Test
    void roundTripsAttributesContainingJsonMetacharacters() {
        // Annotation attributes are free text a library author wrote. Quotes and backslashes in
        // one must not be able to end the string early and turn the rest of the rule into syntax.
        TransitiveRule awkward = rule("com.acme.api", "@AISecure",
            "aspect", "use \"CryptoFactory\", never new Cipher(\\raw\\)");
        List<TransitiveRule> parsed = TransitiveManifest.parse(
            TransitiveManifest.toJson("com.acme.api", "com.acme:core:1.0", List.of(awkward), "9.9.9"),
            "com.acme.api");
        assertEquals(List.of(awkward), parsed);
    }

    @Test
    void serialisationIsByteStableAcrossCallsAndInputOrder() {
        // The manifest ends up inside a published JAR and inside the consumer's build fingerprint.
        // Instability there churns everyone's committed files and breaks reproducible builds.
        List<TransitiveRule> a = List.of(
            rule("com.acme.api", "@AISecure", "aspect", "x"),
            rule("com.acme.api", "@AIAudit", "checkFor", "injection"),
            rule("com.acme.api", "@AIPerformance", "constraint", "hot"));
        List<TransitiveRule> shuffled = List.of(a.get(2), a.get(0), a.get(1));

        String first = TransitiveManifest.toJson("com.acme.api", "o", a, "1.0");
        String second = TransitiveManifest.toJson("com.acme.api", "o", shuffled, "1.0");
        assertEquals(first, second,
            "rule order in memory must not reach the file; discovery order is not stable");
        assertEquals(first, TransitiveManifest.toJson("com.acme.api", "o", a, "1.0"),
            "two calls with identical input must produce identical bytes");
    }

    @Test
    void emptyRuleListStillProducesAValidDocument() {
        String json = TransitiveManifest.toJson("com.acme.api", "", List.of(), "1.0");
        assertEquals(List.of(), TransitiveManifest.parse(json, "com.acme.api"));
    }

    @Test
    void aRuleWithNoAttributesRoundTrips() {
        TransitiveRule bare = rule("com.acme.api", "@AIImmutable");
        String json = TransitiveManifest.toJson("com.acme.api", "com.acme:core:1.0", List.of(bare), "1.0");
        assertEquals(List.of(bare), TransitiveManifest.parse(json, "com.acme.api"));
    }

    @Test
    void aNewerFormatVersionIsSkippedRatherThanGuessedAt() {
        String future = """
            {"manifestVersion": 99, "origin": "x", "package": "com.acme.api",
             "rules": [{"annotation": "@AISecure", "tier": "SAFETY", "members": {}}]}""";
        assertEquals(List.of(), TransitiveManifest.parse(future, "com.acme.api"),
            "half-understanding a rule is worse than ignoring it: the output is instructions an "
                + "agent acts on");
    }

    @Test
    void tierIsRederivedRatherThanTrusted() {
        // A JAR that claimed SAFETY for an advisory rule would buy itself a place in the tier the
        // volume cap never drops, and a heading that says the constraint is not negotiable.
        String lying = """
            {"manifestVersion": 1, "origin": "x", "package": "com.acme.api",
             "rules": [{"annotation": "@AIPerformance", "tier": "SAFETY", "members": {}}]}""";
        List<TransitiveRule> parsed = TransitiveManifest.parse(lying, "com.acme.api");
        assertEquals(1, parsed.size());
        assertEquals(TransitiveRule.Tier.ADVISORY, parsed.get(0).tier(),
            "the tier is a function of the annotation, not something a JAR may assert");
    }

    @Test
    void safetyAnnotationsGetTheSafetyTier() {
        for (String label : List.of("@AILocked", "@AICore", "@AIPrivacy", "@AIIgnore", "@AIAudit", "@AISecure")) {
            assertEquals(TransitiveRule.Tier.SAFETY, TransitiveManifest.tierOf(label), label);
        }
        assertEquals(TransitiveRule.Tier.ADVISORY, TransitiveManifest.tierOf("@AIPerformance"));
    }

    @Test
    void theSafetyListMatchesTheAnnotationsItNames() {
        // The same six the scoped-rules index keeps inline. Kept as one list on purpose; this
        // asserts the derived label set has not drifted from the class set beside it.
        assertEquals(6, TransitiveManifest.SAFETY_ANNOTATIONS.size());
        assertEquals(TransitiveManifest.SAFETY_ANNOTATIONS.size(), TransitiveManifest.SAFETY_LABELS.size(),
            "every safety annotation must contribute exactly one label");
    }

    @Test
    void aMissingPackageFieldFallsBackToTheNameTheReaderAskedFor() {
        String noPackage = """
            {"manifestVersion": 1, "origin": "x",
             "rules": [{"annotation": "@AISecure", "members": {}}]}""";
        assertEquals("com.acme.fallback",
            TransitiveManifest.parse(noPackage, "com.acme.fallback").get(0).packageName());
    }

    @Test
    void rejectsDocumentsThatAreNotManifests() {
        assertThrows(IllegalArgumentException.class,
            () -> TransitiveManifest.parse("{\"rules\": []}", "p"),
            "no manifestVersion means this is not a manifest");
        assertThrows(IllegalArgumentException.class,
            () -> TransitiveManifest.parse("{\"manifestVersion\": 1}", "p"),
            "no rules array means this is not a manifest");
        assertThrows(IllegalArgumentException.class,
            () -> TransitiveManifest.parse("{\"manifestVersion\": 1, \"rules\": [\"oops\"]}", "p"));
        assertThrows(IllegalArgumentException.class,
            () -> TransitiveManifest.parse(
                "{\"manifestVersion\": 1, \"rules\": [{\"members\": {}}]}", "p"),
            "a rule with no annotation names nothing");
        assertThrows(Json.JsonException.class,
            () -> TransitiveManifest.parse("not json at all", "p"));
    }

    @Test
    void theResourcePathIsAValidJavaPackage() {
        // Load-bearing, and measured: javac's CLASS_PATH location skips archive directories whose
        // names are not valid package identifiers, so a manifest under META-INF/ is unreadable
        // from an annotation processor. TransitiveGuardrailLifecycleE2ETest proves that end to end.
        for (String segment : TransitiveManifest.RESOURCE_PACKAGE.split("\\.")) {
            assertTrue(javax.lang.model.SourceVersion.isIdentifier(segment)
                    && !javax.lang.model.SourceVersion.isKeyword(segment),
                "'" + segment + "' must be a legal Java identifier or javac cannot see the manifest");
        }
        assertFalse(TransitiveManifest.RESOURCE_PACKAGE.contains("-"),
            "a hyphen makes the directory invisible to Filer.getResource on CLASS_PATH");
    }

    @Test
    void resourceNameIsThePackagePlusTheExtension() {
        assertEquals("com.acme.api.json", TransitiveManifest.resourceNameFor("com.acme.api"));
    }
}
