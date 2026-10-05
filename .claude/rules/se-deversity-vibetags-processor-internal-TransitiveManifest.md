---
paths: ["**/TransitiveManifest.java"]
---

<!-- VIBETAGS-START -->
# Rules for TransitiveManifest

### Rules for field RESOURCE_PACKAGE
- **Reason**: Must stay a valid Java package name. javac's CLASS_PATH location skips archive directories that are not package identifiers, so moving these manifests under META-INF/ leaves Filer.getResource listing zero entries and transitive discovery fails silently while the conventional location looks correct. TransitiveManifestPathTest pins the working path.

## Context & Focus
- **Focus**: Manifests are read out of JARs already on Maven Central by other processor versions, so a field renamed or redefined needs a FORMAT_VERSION bump
- **Avoid**: Changing the JSON shape in place: older consumers misread it as version 1. A bump has a cost too: older processors skip the manifest and drop its inherited rules
<!-- VIBETAGS-END -->
