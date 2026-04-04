# VibeTags Plugin Architecture

## Current State: Annotation Processor

VibeTags currently uses a Java annotation processor to generate AI guardrail files. This works well for Java projects and requires zero configuration — just add the dependency and annotate your code.

**How it works today:**
```java
// Developer adds annotations
@AILocked(reason = "Legacy system integration")
public class PaymentProcessor { }

// Compiler picks up annotations via SPI
// META-INF/services/javax.annotation.processing.Processor

// AIGuardrailProcessor runs during compilation
// Generates: .cursorrules, CLAUDE.md, .aiexclude, chatgpt_instructions.md
// Output: Project root (via Paths.get(""))
```

## Problems with Annotation Processor Approach

| Issue | Impact |
|-------|--------|
| **Wrong conceptual fit** | Generating config files isn't a compilation task |
| **Output location** | `Paths.get("")` is fragile; breaks in IDEs/subdirectory builds |
| **Java-only** | Python, JS, Go, Rust projects can't use annotation processors |
| **Runs every compile** | Wastes build time even when source hasn't changed |
| **Limited configuration** | Can't easily customize output paths, formats, or filters |
| **Incremental builds** | May regenerate unnecessarily or miss changes |

## Proposed Architecture: Build Plugins + CLI

```
vibetags/
├── annotations/              # Pure annotation JAR (@AILocked, @AIContext, @AIDraft)
├── core/                     # Shared parsing + generation logic
│   ├── TagScanner           # Scans source files for annotations/metadata
│   ├── GuardrailGenerator   # Generates platform-specific AI config
│   └── ConfigLoader         # Reads vibetags.yaml configuration
├── cli/                      # Standalone CLI tool (any language)
│   └── vibetags scan --source ./src --output . --config vibetags.yaml
├── maven-plugin/             # Maven integration
│   └── vibetags:generate
├── gradle-plugin/            # Gradle integration
│   └── tasks { vibetagsGenerate }
└── annotation-processor/     # Legacy support (wraps core)
    └── Deprecated, delegates to core
```

## Component Design

### 1. Core Library (`vibetags-core`)

Shared logic that all plugins use. Language-agnostic at its heart.

```java
public interface TagScanner {
    List<GuardrailTag> scan(Path sourceDir, ScanOptions options);
}

public interface GuardrailGenerator {
    List<GeneratedFile> generate(List<GuardrailTag> tags, GeneratorConfig config);
}

// Tag types
public sealed interface GuardrailTag {
    record Locked(String file, String reason, Location location) implements GuardrailTag {}
    record Context(String file, String focus, String[] avoids, Location location) implements GuardrailTag {}
    record Draft(String file, String instructions, Location location) implements GuardrailTag {}
}

// Generated output
public record GeneratedFile(String name, String content, String format) {
    // format: "cursorrules", "claude", "aiexclude", "chatgpt"
}
```

**Key benefit:** Same core logic works across Maven, Gradle, CLI, and future integrations.

### 2. CLI Tool (`vibetags-cli`)

Works with **any project**, any language.

```bash
# Basic usage (scan Java source)
vibetags scan --source ./src/main/java --output .

# Any language with inline comments
vibetags scan --source ./src --output . --mode comments

# Custom config
vibetags scan --config vibetags.yaml

# Watch mode for development
vibetags watch --source ./src --output . --interval 5s

# CI/CD validation
vibetags verify --source ./src --expect-no-violations
```

**Configuration (`vibetags.yaml`):**
```yaml
source:
  paths:
    - src/main/java
    - src/test/java
  include: ["**/*.java"]
  exclude: ["**/generated/**"]

output:
  directory: .
  formats:
    - cursorrules
    - claude
    - aiexclude
    - chatgpt

annotations:
  process:
    - AILocked
    - AIContext
    - AIDraft
  ignore: []

custom:
  additional_rules:
    - name: "Always use dependency injection"
      scope: project
```

### 3. Maven Plugin (`vibetags-maven-plugin`)

```xml
<plugin>
    <groupId>se.deversity.vibetags</groupId>
    <artifactId>vibetags-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <sourceDirectories>
            <sourceDirectory>${project.basedir}/src/main/java</sourceDirectory>
        </sourceDirectories>
        <outputDirectory>${project.basedir}</outputDirectory>
        <formats>
            <format>cursorrules</format>
            <format>claude</format>
            <format>aiexclude</format>
            <format>chatgpt</format>
        </formats>
    </configuration>
    <executions>
        <execution>
            <phase>process-sources</phase>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Usage:**
```bash
# Generate guardrail files
mvn vibetags:generate

# Part of normal build lifecycle (auto-runs in process-sources phase)
mvn clean compile
```

**Implementation:**
```java
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class VibeTagsMojo extends AbstractMojo {

    @Parameter(required = true, defaultValue = "${project.basedir}")
    private File outputDirectory;

    @Parameter
    private List<String> sourceDirectories;

    @Override
    public void execute() throws MojoExecutionException {
        TagScanner scanner = new JavaTagScanner();
        GuardrailGenerator generator = new GuardrailGenerator();

        List<GuardrailTag> tags = sourceDirectories.stream()
            .map(Path::of)
            .flatMap(dir -> scanner.scan(dir).stream())
            .toList();

        List<GeneratedFile> files = generator.generate(tags, config);

        files.forEach(file -> {
            Path target = outputDirectory.resolve(file.name());
            Files.writeString(target, file.content());
            getLog().info("Generated: " + target);
        });
    }
}
```

### 4. Gradle Plugin (`vibetags-gradle-plugin`)

```kotlin
plugins {
    id("se.deversity.vibetags.generator") version "1.0.0"
}

vibetags {
    sourceDirs.setFrom(
        file("src/main/java"),
        file("src/test/java")
    )
    outputDir.set(layout.projectDirectory)
    formats.set(listOf("cursorrules", "claude", "aiexclude", "chatgpt"))
}
```

**Usage:**
```bash
# Generate guardrail files
gradle vibetagsGenerate

# Run as part of build
gradle build
```

**Implementation:**
```kotlin
abstract class VibeTagsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "vibetags",
            VibeTagsExtension::class.java
        )

        project.tasks.register("vibetagsGenerate", VibeTagsTask::class.java) {
            it.sourceDirs.set(extension.sourceDirs)
            it.outputDir.set(extension.outputDir)
            it.formats.set(extension.formats)
        }
    }
}

abstract class VibeTagsTask : DefaultTask() {
    @get:InputFiles
    abstract val sourceDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val scanner = JavaTagScanner()
        val generator = GuardrailGenerator()

        val tags = sourceDirs.files.flatMap { dir ->
            scanner.scan(dir.toPath())
        }

        val files = generator.generate(tags)
        files.forEach { file ->
            val target = outputDir.get().file(file.name).asFile
            target.writeText(file.content)
            logger.info("Generated: ${target.absolutePath}")
        }
    }
}
```

## Migration Path

### Phase 1: Extract Core (Current → v1.1)

- [ ] Move parsing/generation logic from annotation processor to `vibetags-core`
- [ ] Keep annotation processor as thin wrapper around core
- [ ] No breaking changes for existing users

### Phase 2: Add CLI (v1.1 → v1.2)

- [ ] Build standalone CLI tool using core library
- [ ] Support comment-based annotations for non-Java projects:
  ```python
  # @AILocked: Legacy payment integration. Changes require approval from finance team.
  def process_payment(amount, currency):
      pass
  ```
- [ ] Add `vibetags.yaml` configuration support

### Phase 3: Build Plugins (v1.2 → v2.0)

- [ ] Create Maven plugin with proper lifecycle integration
- [ ] Create Gradle plugin with task configuration avoidance API
- [ ] Deprecate annotation processor (keep for backwards compatibility)
- [ ] Update documentation and examples

## Comparison: Current vs Proposed

| Aspect | Annotation Processor (Current) | Plugins + CLI (Proposed) |
|--------|-------------------------------|--------------------------|
| **Setup** | Zero config (just add dependency) | Requires plugin config |
| **Output location** | `Paths.get("")` (fragile) | Explicit, configurable |
| **Language support** | Java only | Any language (via CLI) |
| **Build integration** | Automatic (SPI) | Explicit plugin/task |
| **Configuration** | Compiler args only | Rich YAML/TOML config |
| **Incremental builds** | Compiler-managed | Task input/output tracking |
| **IDE support** | Varies | Explicit tasks visible |
| **CI/CD** | Must compile first | Standalone step |
| **Custom formats** | Hard to extend | Plugin system possible |
| **Maintenance** | One codebase | 3-4 codebases to maintain |

## Why This Matters

### Today's User
> "I added the dependency, annotated my code, ran `mvn compile`, and got all four AI config files. It just works."

### Tomorrow's User (with plugins)
> "I'm working on a Python project. I installed the VibeTags CLI, ran `vibetags scan`, and got all four AI config files. It just works."

### Non-Java Project
```python
# @AILocked: PCI-DSS compliance. Changes require security review ticket SEC-XXXX
@AILocked(reason="PCI-DSS compliance. Changes require security review.")
def encrypt_card_data(card_number: str) -> str:
    # ...
    pass

# @AIContext: Optimize for memory, avoid pandas (use polars)
# @AIDraft: Implement using FastAPI, add Pydantic validation and rate limiting
async def upload_file(file: UploadFile):
    # TODO: Implement
    pass
```

The CLI scans these comments and generates the same AI config files as the Java annotation processor.

## Backwards Compatibility

The annotation processor would remain available but marked as **legacy**:

```xml
<!-- Still works, but delegates to core -->
<dependency>
    <groupId>se.deversity.vibetags</groupId>
    <artifactId>vibetags-processor</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
    <!-- Deprecated: Use vibetags-maven-plugin instead -->
</dependency>
```

Existing projects continue to work without changes. New projects are encouraged to use the build plugins or CLI.

## Open Questions

1. **Should we keep the annotation processor forever?** Or sunset it after v2.0?
2. **How do we handle non-Java annotation syntax?** Comments? AST parsing? Language servers?
3. **Should generated files go in version control?** They're derived artifacts, but AI tools need them at root.
4. **Custom AI platform support?** Let users define generators for new AI tools beyond the big four.
5. **Validation mode?** `vibetags verify` that checks if generated files are up-to-date (useful for CI).

## Decision: Is It Worth It?

### Stay with Annotation Processor If:
- ✅ VibeTags is Java-only forever
- ✅ Current users are happy
- ✅ No demand for other languages
- ✅ Maintenance simplicity is priority

### Build Plugins + CLI If:
- 🚀 VibeTags should work with any project (Python, JS, Go, Rust, etc.)
- 🚀 Better build integration is needed (proper incremental builds, IDE tasks)
- 🚀 Users want richer configuration
- 🚀 CI/CD needs a standalone validation step

**Recommendation:** Monitor adoption. If non-Java users ask for support, build the CLI first (lowest effort, highest impact). If Java users demand better build integration, add Maven/Gradle plugins next.
