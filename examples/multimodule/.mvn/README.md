# Why this directory exists

The presence of a `.mvn/` directory at the reactor root anchors Maven's
`${maven.multiModuleProjectDirectory}` property to this directory, no matter which module the
build is invoked from. The parent POM passes that property as `-Avibetags.root`, so every
module writes its VibeTags guardrails into the same shared root and the generated files
aggregate annotations from all modules.

Do not add a `maven.config` here unless you actually need extra CLI options — its lines are
passed verbatim to Maven.
