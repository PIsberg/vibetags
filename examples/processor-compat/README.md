# Processor coexistence example

VibeTags on one `<annotationProcessorPaths>` with Lombok and MapStruct. `Customer` and
`CustomerDto` get their accessors from Lombok, `CustomerMapper` gets its implementation from
MapStruct (through `lombok-mapstruct-binding`, so MapStruct sees those accessors), and
`CustomerService` calls both, so the example compiles only when every processor ran.

```bash
cd examples/processor-compat
mvn clean compile                    # all three processors; regenerates CLAUDE.md
mvn clean compile -Pvibetags-only    # the mistake: VibeTags alone on the list
```

The second build fails, as it does in any project that pastes a VibeTags-only processor path into
a pom whose Lombok ran by class-path discovery: javac stops discovering processors once
`<annotationProcessorPaths>` exists. VibeTags names what was dropped, at the annotated element:

```
[WARNING] .../Customer.java:[10,8] VibeTags: @lombok.Data needs the Lombok annotation processor,
which this compilation did not run. Once maven-compiler-plugin has <annotationProcessorPaths>,
javac ignores processors on the class path: add org.projectlombok:lombok to that list beside
vibetags-processor. ...
[WARNING] .../CustomerMapper.java:[10,8] VibeTags: @org.mapstruct.Mapper needs the MapStruct
annotation processor, ...
[ERROR] warnings found and -Werror specified
```

Under this example's `-Werror` those warnings end the build. Without it, the next lines are
javac's `cannot find symbol` on the Lombok accessor `CustomerService` calls.

The build runs with `-Xlint:all,-processing -Werror`, which shows VibeTags adds no warning of its
own to a correctly wired strict build. The `processing` lint stays off because javac reports every annotation no
processor claims, MapStruct's `@Mapper` included, whether or not VibeTags is present.

CI runs both builds on every push, checks the committed `CLAUDE.md` byte for byte, and requires
the second build to fail with both processors named. The wiring choices, the Spring Boot layout
and the list of recognised processors: [USAGE.md](../../USAGE.md#other-annotation-processors).
