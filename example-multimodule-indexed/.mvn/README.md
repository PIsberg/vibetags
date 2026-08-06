# .mvn marker

This directory anchors `${maven.multiModuleProjectDirectory}` to the reactor root, so every
module resolves the same VibeTags root (`-Avibetags.root=${maven.multiModuleProjectDirectory}`
in the parent POM). See the parent `pom.xml`.
