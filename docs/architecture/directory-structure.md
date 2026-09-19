# Architecture: Directory Structure

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Directory Structure

```
vibetags/
├── vibetags-annotations/              # Published as se.deversity.vibetags:vibetags-annotations
│   ├── src/main/java/se/deversity/vibetags/annotations/
│   │   ├── AILocked.java
│   │   ├── AIContext.java
│   │   ├── ...                          # every @interface — see ../README.md#project-facts
│   │   └── AITemporary.java
│   ├── pom.xml
│   └── build.gradle
│
├── vibetags/                          # Published as se.deversity.vibetags:vibetags-processor
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/se/deversity/vibetags/processor/
│   │   │   │   ├── AIGuardrailProcessor.java     # JSR 269 orchestrator; delegates to internal/
│   │   │   │   ├── VibeTagsLogger.java           # SLF4J/Logback file logger
│   │   │   │   ├── internal/                     # javac-facing helpers
│   │   │   │   │   ├── AnnotationCollector.java       # one LinkedHashSet per annotation type; model() snapshots them
│   │   │   │   │   ├── AnnotationValidator.java       # Entry point for compile-time consistency warnings
│   │   │   │   │   ├── validation/                    # The checks themselves: PairRule, CoreRules,
│   │   │   │   │   │                                  #   ArchitectureRule, ModernJavaRules + registry
│   │   │   │   │   ├── OrphanWarner.java              # "annotation used but ignore-file missing"
│   │   │   │   │   ├── ServiceRegistry.java           # Service map + file-existence opt-in
│   │   │   │   │   ├── ElementNaming.java             # elementPath / displayName helpers
│   │   │   │   │   ├── GuardrailContentBuilder.java   # Thin coordinator delegating to PlatformRenderers
│   │   │   │   │   ├── GuardrailFileWriter.java       # Marker-aware atomic writes + cache + streaming compare
│   │   │   │   │   ├── GranularRulesWriter.java       # Per-class .mdc/.md + orphan cleanup
│   │   │   │   │   ├── WriteCache.java                # 0.7.1: per-file content cache (.vibetags-cache sidecar)
│   │   │   │   │   └── content/                       # Rendering — compiler-free, reads the model only
│   │   │   │   │       ├── annotations/               # one AI*Formatter per annotation
│   │   │   │   │       └── platforms/                 # one PlatformRenderer per output file
│   │   │   │   └── model/                        # The compiler-free seam: internal → model ← content
│   │   │   │       ├── GuardrailModel.java            # immutable snapshot the renderers read
│   │   │   │       ├── TaggedElement.java             # one annotated element as plain data
│   │   │   │       ├── ElementTag.java                # name-for-name mirror of ElementKind
│   │   │   │       ├── GuardrailAnnotations.java      # the single ordered annotation registry
│   │   │   │       ├── RoleConfig.java                # .vibetags-roles routing
│   │   │   │       ├── SourceLocation.java            # file + line range for .vibetags-locks
│   │   │   │       └── ContentHash.java               # the shared 8-hex content hash
│   │   │   └── resources/META-INF/services/
│   │   │       └── javax.annotation.processing.Processor
│   │   └── test/                      # Unit + integration tests (424 tests total)
│   │       └── processor/
│   │           ├── AnnotationDefinitionsTest.java
│   │           ├── AIGuardrailProcessorTest.java
│   │           ├── AIGuardrailProcessorUnitTest.java
│   │           ├── AIGuardrailProcessorProcessTest.java
│   │           ├── AIIgnoreProcessorUnitTest.java
│   │           ├── AIPrivacyProcessorTest.java
│   │           ├── AIContractProcessorTest.java               # 0.7.0: @AIContract coverage
│   │           ├── CleanupGranularDirectoryTest.java          # 0.6.0: orphan-removal coverage
│   │           ├── WriteFileFrontMatterTest.java              # 0.6.0: YAML front-matter coverage
│   │           ├── StripLegacyVibeTagsBlockEdgeCasesTest.java # 0.6.0: legacy migration edges
│   │           ├── WriteCacheTest.java                        # 0.7.1: cache hit/miss/invalidation
│   │           ├── WriteCacheProcessorIntegrationTest.java    # 0.7.1: cache E2E via processor
│   │           ├── StreamingByteCompareTest.java              # 0.7.1: fileBytesEqual helper
│   │           ├── GuardrailFileWriterCoverageTest.java       # 0.7.1: streaming + noopMessager
│   │           ├── QwenProcessorUnitTest.java
│   │           ├── NewPlatformsEndToEndTest.java
│   │           ├── GranularRulesEndToEndTest.java
│   │           ├── MultiModuleStabilityTest.java
│   │           ├── AnnotationProcessorEndToEndTest.java
│   │           └── QwenEndToEndTest.java
│   ├── pom.xml                        # Maven build config (depends on vibetags-annotations)
│   └── build.gradle                   # Gradle build config
│
├── vibetags-bom/                      # Published as se.deversity.vibetags:vibetags-bom (pom-only)
│   └── pom.xml                        # <dependencyManagement> for vibetags-annotations + vibetags-processor
│
├── examples/basic/                           # Demo e-commerce application
│   ├── src/main/java/com/example/
│   │   ├── database/
│   │   │   └── DatabaseConnector.java         # @AIAudit example
│   │   ├── internal/
│   │   │   └── GeneratedMetadata.java         # @AIIgnore example
│   │   ├── payment/
│   │   │   └── PaymentProcessor.java          # @AILocked example
│   │   ├── security/
│   │   │   └── SecurityConfig.java            # @AILocked + @AIContext
│   │   └── ...                                # More examples
│   ├── QWEN.md                        # Generated: Qwen project context
│   ├── .qwen/                         # Generated: Qwen directory
│   │   ├── settings.json              # Generated: Qwen model settings
│   │   └── commands/
│   │       └── refactor.md            # Generated: Qwen custom command
│   ├── .qwenignore                    # Generated: Qwen exclusion list
│   ├── .cursorrules                   # Generated: Cursor rules
│   ├── CLAUDE.md                      # Generated: Claude guardrails
│   ├── llms.txt                       # Generated: llms.txt standard (concise map)
│   ├── llms-full.txt                  # Generated: llms.txt standard (full reference)
│   └── ...                            # Other AI config files
│
├── docs/                              # Documentation
│   ├── ARCHITECTURE.md                # Architecture index; parts in architecture/
│   └── diagrams/                      # Hand-drawn PlantUML + parsed code-karta SVG
│       ├── build-sequence.puml        # Hand-drawn: PlantUML source
│       ├── build-sequence.png         #             rendered by generate.cjs
│       ├── component-diagram.puml
│       ├── component-diagram.png
│       ├── data-flow.puml
│       ├── data-flow.png
│       ├── platform-output.puml
│       ├── platform-output.png
│       ├── codekarta/                 # Parsed: tools/generate-architecture-diagrams.sh
│       │   ├── class-diagram.svg              # processor
│       │   ├── model/class-diagram.svg        # processor.model
│       │   ├── content/class-diagram.svg      # internal.content
│       │   ├── annotations/class-diagram.svg  # the 44 @AI* types
│       │   └── sequence/                      # AIGuardrailProcessor call order
│       └── archive/                   # Superseded hand-drawn diagrams, kept for history
│
├── .gitignore
├── README.md
└── package.json
```
