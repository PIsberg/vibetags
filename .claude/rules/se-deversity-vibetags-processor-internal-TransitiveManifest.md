---
paths: ["**/TransitiveManifest.java"]
---

<!-- VIBETAGS-START -->
# Rules for TransitiveManifest

### Rules for field RESOURCE_PACKAGE
- **Reason**: Must stay a valid Java package name. javac's CLASS_PATH location skips archive directories that are not package identifiers, so moving these manifests under META-INF/ leaves Filer.getResource listing zero entries and transitive discovery fails silently while the conventional location looks correct. TransitiveManifestPathTest pins the working path.
<!-- VIBETAGS-END -->
