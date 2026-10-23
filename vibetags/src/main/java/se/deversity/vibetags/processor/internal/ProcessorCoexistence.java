package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Names a peer annotation processor that this compilation's source depends on but javac never ran.
 *
 * <p>maven-compiler-plugin stops discovering processors on the class path as soon as
 * {@code <annotationProcessorPaths>} is set. A project whose Lombok or MapStruct ran by discovery
 * loses both the moment it adds a VibeTags-only processor path, and javac's only symptom is
 * "cannot find symbol" on a getter Lombok never generated, or for MapStruct a mapper
 * implementation that is missing at run time. Reproduced on 2026-10-19 against Lombok 1.18.42 and
 * MapStruct 1.6.3 with the release then current. Gradle drops a processor the same way when it sits on
 * {@code compileOnly} but not {@code annotationProcessor}.
 *
 * <p>javac loads every processor on one processor path through one class loader, the one that
 * loaded VibeTags; with class-path discovery that loader is the class path's. So a peer whose
 * processor class that loader cannot see did not run in this compilation. A peer the source does
 * not use is never reported, however its jars are wired.
 *
 * <p>The one peer known to run without being on the processor path is Lombok as a java agent, how
 * the Eclipse IDE runs it (#990). {@code -javaagent} puts the jar on the JVM's own class path, so a
 * peer the system class loader can see counts as present too. No verified wiring puts a processor
 * there otherwise: Maven's in-process javac, the {@code javac} launcher and Gradle's daemon each
 * have a JVM class path of their own tooling, so the check loses nothing where it was measured.
 * ECJ 3.46.100 with {@code -javaagent:lombok.jar=ECJ} and VibeTags alone on {@code -processorpath}
 * builds and prints nothing (its processor loader already delegates to the JVM class path), and
 * reports Lombok without the agent, where the build fails on the getter.
 */
public final class ProcessorCoexistence {

    /**
     * A processor whose annotations do nothing without it.
     *
     * @param name           how the warning names it
     * @param coordinates    the Maven coordinates to add to the processor path, without a version
     * @param annotations    trigger annotations: source using any of them depends on the processor
     * @param processorClass a class every release of the processor jar carries
     */
    public record Peer(String name, String coordinates, List<String> annotations, String processorClass) {
        /** Defensive copy, so the table cannot change after it is built. */
        public Peer {
            annotations = List.copyOf(annotations);
        }
    }

    /**
     * The peers recognised. Class names were read off each jar's
     * {@code META-INF/services/javax.annotation.processing.Processor}, and every trigger annotation
     * off its API jar, on 2026-10-19.
     */
    public static final List<Peer> PEERS = List.of(
        new Peer("Lombok", "org.projectlombok:lombok",
            List.of("lombok.Data", "lombok.Getter", "lombok.Setter", "lombok.Builder", "lombok.Value",
                "lombok.AllArgsConstructor", "lombok.NoArgsConstructor", "lombok.RequiredArgsConstructor",
                "lombok.ToString", "lombok.EqualsAndHashCode", "lombok.With", "lombok.extern.slf4j.Slf4j"),
            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor"),
        new Peer("MapStruct", "org.mapstruct:mapstruct-processor",
            List.of("org.mapstruct.Mapper"), "org.mapstruct.ap.MappingProcessor"),
        new Peer("Immutables", "org.immutables:value",
            List.of("org.immutables.value.Value.Immutable"), "org.immutables.processor.ProxyProcessor"),
        new Peer("AutoValue", "com.google.auto.value:auto-value",
            List.of("com.google.auto.value.AutoValue", "com.google.auto.value.AutoBuilder"),
            "com.google.auto.value.processor.AutoValueProcessor"),
        new Peer("Dagger", "com.google.dagger:dagger-compiler",
            List.of("dagger.Component", "dagger.Module"), "dagger.internal.codegen.ComponentProcessor"),
        new Peer("RecordBuilder", "io.soabase.record-builder:record-builder-processor",
            List.of("io.soabase.recordbuilder.core.RecordBuilder"),
            "io.soabase.recordbuilder.processor.RecordBuilderProcessor"),
        new Peer("AutoService", "com.google.auto.service:auto-service",
            List.of("com.google.auto.service.AutoService"),
            "com.google.auto.service.processor.AutoServiceProcessor"));

    private ProcessorCoexistence() {}

    /**
     * As {@link #warnAboutDroppedPeers(ProcessingEnvironment, RoundEnvironment, ClassLoader, Logger)},
     * against the loader that loaded VibeTags. This class ships in the processor jar, so its loader
     * is that one.
     */
    @SuppressWarnings("PMD.UseProperClassLoader") // the processor path's loader is the point; a context loader answers for another path
    public static List<Peer> warnAboutDroppedPeers(ProcessingEnvironment env, RoundEnvironment round,
                                                   @Nullable Logger log) {
        return warnAboutDroppedPeers(env, round, ProcessorCoexistence.class.getClassLoader(), log);
    }

    /**
     * As {@link #warnAboutDroppedPeers(ProcessingEnvironment, RoundEnvironment, ClassLoader, ClassLoader, Logger)},
     * with the JVM's system class loader as {@code jvmLoader}.
     */
    public static List<Peer> warnAboutDroppedPeers(ProcessingEnvironment env, RoundEnvironment round,
                                                   ClassLoader processorLoader, @Nullable Logger log) {
        return warnAboutDroppedPeers(env, round, processorLoader, ClassLoader.getSystemClassLoader(), log);
    }

    /**
     * Warns once for each peer the round's source uses whose processor neither
     * {@code processorLoader} nor {@code jvmLoader} can load. Call it on the first round: later
     * rounds only add generated sources.
     *
     * <p>Never throws. The check is advisory, and anything escaping it would reach
     * {@code process()}'s catch-all, which ends the round before validation and generation run.
     *
     * @param processorLoader the loader that loaded the calling processor
     * @param jvmLoader       the loader for the JVM's own class path, where {@code -javaagent} puts
     *                        a peer that runs outside the processor path
     * @param log             the diagnostic log, or {@code null}
     * @return the peers reported, in {@link #PEERS} order
     */
    public static List<Peer> warnAboutDroppedPeers(ProcessingEnvironment env, RoundEnvironment round,
                                                   ClassLoader processorLoader, ClassLoader jvmLoader,
                                                   @Nullable Logger log) {
        try {
            return check(env, round, processorLoader, jvmLoader, log);
        } catch (RuntimeException e) {
            if (log != null) {
                log.debug("coexistence.skip reason=error detail={}", String.valueOf(e));
            }
            return List.of();
        }
    }

    private static List<Peer> check(ProcessingEnvironment env, RoundEnvironment round,
                                    ClassLoader processorLoader, ClassLoader jvmLoader, @Nullable Logger log) {
        List<Peer> dropped = new ArrayList<>();
        for (Peer peer : PEERS) {
            Use use = firstUse(env, round, peer);
            if (use == null) {
                continue;
            }
            if (loads(processorLoader, peer.processorClass())) {
                if (log != null && log.isDebugEnabled()) {
                    log.debug("coexistence.peer name={} annotation={} state=on-processor-path", peer.name(), use.annotation());
                }
                continue;
            }
            if (loads(jvmLoader, peer.processorClass())) {
                if (log != null && log.isDebugEnabled()) {
                    log.debug("coexistence.peer name={} annotation={} state=on-jvm-class-path", peer.name(), use.annotation());
                }
                continue;
            }
            dropped.add(peer);
            env.getMessager().printMessage(Diagnostic.Kind.WARNING, message(peer, use.annotation()), use.element());
            if (log != null) {
                log.warn("coexistence.peer-dropped name={} annotation={} element={}",
                    peer.name(), use.annotation(), use.element());
            }
        }
        return dropped;
    }

    static String message(Peer peer, String annotation) {
        return "VibeTags: @" + annotation + " needs the " + peer.name() + " annotation processor, which this"
            + " compilation did not run. Once maven-compiler-plugin has <annotationProcessorPaths>, javac"
            + " ignores processors on the class path: add " + peer.coordinates() + " to that list beside"
            + " vibetags-processor. On Gradle, add it to the annotationProcessor configuration. See"
            + " https://github.com/PIsberg/vibetags/blob/main/USAGE.md#other-annotation-processors";
    }

    private record Use(String annotation, Element element) {
    }

    private static @Nullable Use firstUse(ProcessingEnvironment env, RoundEnvironment round, Peer peer) {
        for (String annotation : peer.annotations()) {
            TypeElement type = env.getElementUtils().getTypeElement(annotation);
            if (type == null) {
                continue; // not on the compile class path, so nothing here can use it
            }
            Set<? extends Element> users = round.getElementsAnnotatedWith(type);
            if (!users.isEmpty()) {
                return new Use(annotation, users.iterator().next());
            }
        }
        return null;
    }

    /**
     * Whether {@code loader} can see the class. A class that is present but fails to link still
     * counts as present: the question is whether the jar is on the path, and a false alarm about
     * a processor that is there costs more than silence about one that is broken for another reason.
     */
    private static boolean loads(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (LinkageError presentButUnlinkable) {
            return true;
        }
    }
}
