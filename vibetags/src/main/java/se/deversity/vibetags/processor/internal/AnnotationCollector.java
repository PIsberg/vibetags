package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Aggregates annotated elements across the multiple processing rounds {@code javac} performs.
 * Each annotation type has its own {@link LinkedHashSet} (insertion-ordered for stable output).
 */
public final class AnnotationCollector {

    private final Set<Element> lockedElements      = new LinkedHashSet<>();
    private final Set<Element> contextElements     = new LinkedHashSet<>();
    private final Set<Element> ignoreElements      = new LinkedHashSet<>();
    private final Set<Element> auditElements       = new LinkedHashSet<>();
    private final Set<Element> draftElements       = new LinkedHashSet<>();
    private final Set<Element> privacyElements     = new LinkedHashSet<>();
    private final Set<Element> coreElements        = new LinkedHashSet<>();
    private final Set<Element> performanceElements = new LinkedHashSet<>();

    private boolean anyAnnotationsFound = false;

    /** Drains the round environment into our per-annotation sets. Returns true if anything was added. */
    public boolean collect(RoundEnvironment roundEnv) {
        lockedElements.addAll(roundEnv.getElementsAnnotatedWith(AILocked.class));
        contextElements.addAll(roundEnv.getElementsAnnotatedWith(AIContext.class));
        ignoreElements.addAll(roundEnv.getElementsAnnotatedWith(AIIgnore.class));
        auditElements.addAll(roundEnv.getElementsAnnotatedWith(AIAudit.class));
        draftElements.addAll(roundEnv.getElementsAnnotatedWith(AIDraft.class));
        privacyElements.addAll(roundEnv.getElementsAnnotatedWith(AIPrivacy.class));
        coreElements.addAll(roundEnv.getElementsAnnotatedWith(AICore.class));
        performanceElements.addAll(roundEnv.getElementsAnnotatedWith(AIPerformance.class));

        boolean added = !lockedElements.isEmpty() || !contextElements.isEmpty()
                     || !ignoreElements.isEmpty() || !auditElements.isEmpty()
                     || !draftElements.isEmpty() || !privacyElements.isEmpty()
                     || !coreElements.isEmpty() || !performanceElements.isEmpty();
        if (added) anyAnnotationsFound = true;
        return added;
    }

    public void reset() {
        lockedElements.clear();
        contextElements.clear();
        ignoreElements.clear();
        auditElements.clear();
        draftElements.clear();
        privacyElements.clear();
        coreElements.clear();
        performanceElements.clear();
        anyAnnotationsFound = false;
    }

    public Set<Element> locked()      { return lockedElements; }
    public Set<Element> context()     { return contextElements; }
    public Set<Element> ignore()      { return ignoreElements; }
    public Set<Element> audit()       { return auditElements; }
    public Set<Element> draft()       { return draftElements; }
    public Set<Element> privacy()     { return privacyElements; }
    public Set<Element> core()        { return coreElements; }
    public Set<Element> performance() { return performanceElements; }
    public boolean anyAnnotationsFound() { return anyAnnotationsFound; }
}
