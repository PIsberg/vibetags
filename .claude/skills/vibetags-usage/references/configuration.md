# VibeTags: Advanced configuration

Part of the `vibetags-usage` skill; [SKILL.md](../SKILL.md) holds setup and the element cheat sheet.

## Advanced Configuration

### Processor options (Maven)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <!-- Set project name in llms.txt / llms-full.txt H1 -->
            <arg>-Avibetags.project=MyProjectName</arg>
            <!-- Custom log path (relative to project root or absolute) -->
            <arg>-Avibetags.log.path=logs/vibetags.log</arg>
            <!-- Log level: TRACE, DEBUG, INFO, WARN, ERROR, OFF -->
            <arg>-Avibetags.log.level=DEBUG</arg>
            <!-- Override output root directory (every module of a reactor needs this) -->
            <arg>-Avibetags.root=${maven.multiModuleProjectDirectory}</arg>
            <!-- Name this module explicitly, if it cannot be read off the compiled sources -->
            <arg>-Avibetags.module=payments-core</arg>
            <!-- CI: verify the committed files match the annotations instead of writing them -->
            <arg>-Avibetags.check=true</arg>
            <!-- Opt-in enforcement: fail the build on a guarded signature change -->
            <arg>-Avibetags.enforce=locked,contract,publicapi</arg>
            <!-- Transitive: coordinate published in this library's manifests -->
            <arg>-Avibetags.manifest.origin=com.acme:crypto-core:2.4.0</arg>
            <!-- Transitive: read manifests from a directory (kapt/ECJ/JPMS fallback) -->
            <arg>-Avibetags.manifest.dir=build/vibetags-manifests</arg>
            <!-- Transitive: look up these packages explicitly, when imports cannot be read -->
            <arg>-Avibetags.manifest.packages=com.acme.crypto.api,com.acme.audit</arg>
            <!-- Transitive: cap inherited advisory rules (safety buckets are never dropped) -->
            <arg>-Avibetags.manifest.max=50</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### Enforcing mode (opt-in)

Guardrails are advisory by default: they go into the agent's context so a mistake is less likely.
For the families whose promise can be *proved* from the compiler's model, `-Avibetags.enforce` turns
that into a hard stop.

```bash
mvn compile -Avibetags.baseline.update=true   # record and commit .vibetags-baseline
mvn compile -Avibetags.enforce=contract       # thereafter, a signature change fails the build
```

| Family | What it checks |
|---|---|
| `locked` | An `@AILocked` element's visible shape is unchanged |
| `contract` | An `@AIContract` signature is unchanged — name, parameters, return type, checked exceptions |
| `publicapi` | Ditto for `@AIPublicAPI` |
| `all` | All of the above |

Method bodies, comments and formatting are invisible to it, so reformatting a locked file is not a
violation. `@AICallersOnly`, `@AIStrictClasspath`, `@AIThreadSafe` and `@AITestDriven` are **not**
enforceable — proving them needs call-graph or body analysis a processor cannot do portably — and
naming one is reported rather than silently ignored. An intended change is approved by re-running
with `-Avibetags.baseline.update=true` and committing the diff, so it gets reviewed.

### Processor options (Gradle)

```groovy
tasks.withType(JavaCompile) {
    options.compilerArgs += [
        '-Avibetags.project=MyProjectName',
        '-Avibetags.log.path=logs/vibetags.log',
        '-Avibetags.log.level=DEBUG'
    ]
}
```
