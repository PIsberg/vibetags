package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reports a compilation that is about to <em>remove</em> guardrails rather than add them.
 *
 * <p>Every multi-module defect VibeTags has shipped so far failed the same way: the output stayed
 * well-formed, the build stayed green, and the only symptom was guardrails quietly going missing —
 * so the natural review reaction to "the guardrails changed" was to accept the regenerated file.
 * Issue #278 was module-vs-module, #330 was round-vs-round within one module, #331 was
 * Maven-vs-Gradle; none of them announced itself. A diagnostic here turns that class of bug from an
 * afternoon of bisecting into a line in the build log.
 *
 * <p>Deliberately narrow, because a warning that fires on ordinary work is a warning people learn
 * to skip. One check needs the replacement set to be <em>disjoint</em> from what the module
 * recorded last time; the other needs the round to remove more rules than it writes. Editing an
 * annotation, or deleting one of many, trips neither.
 */
public final class DestructiveRewriteWarner {

    /** Cap on how many names a single diagnostic lists before it summarises the rest. */
    private static final int MAX_LISTED = 8;

    private final @Nullable Messager messager;
    private final @Nullable Logger log;

    public DestructiveRewriteWarner(@Nullable Messager messager, @Nullable Logger log) {
        this.messager = messager;
        this.log = log;
    }

    /**
     * Warns when this round replaces every element a module previously contributed with a set that
     * shares none of them.
     *
     * <p>This is the exact shape of issue #330: the {@code test-compile} round saw one annotated
     * test class, wrote the module's region from that alone, and the eleven main-source elements the
     * {@code compile} round had written vanished. Both sets non-empty and disjoint means the module
     * did not lose one annotation — it lost all of them and gained different ones, which a source
     * edit essentially never does.
     *
     * @param moduleId       the sidecar id being rewritten (source set included)
     * @param previousStems  the elements the last run recorded for this id; empty on a first build
     * @param newStems       the elements this run is about to record
     */
    public void regionReplaced(String moduleId, Set<String> previousStems, Set<String> newStems) {
        if (previousStems.isEmpty() || newStems.isEmpty()) {
            return; // first build, or a module that legitimately went quiet — the guards cover that
        }
        Set<String> survivors = new LinkedHashSet<>(previousStems);
        survivors.retainAll(newStems);
        if (!survivors.isEmpty()) {
            return; // ordinary churn: at least one element carried over
        }
        List<String> lost = new ArrayList<>(previousStems);
        warn("VibeTags: module '" + moduleId + "' is being rewritten with a completely different set"
                + " of elements — all " + previousStems.size() + " previously recorded guardrails"
                + " (" + summarise(lost) + ") are being replaced by " + newStems.size() + " unrelated"
                + " one(s) (" + summarise(new ArrayList<>(newStems)) + ")."
                + " If this compilation did not see the sources you expected, the regenerated files"
                + " are missing guardrails rather than reflecting a real change.",
            "rewrite.replace module={} lost={} gained={}", moduleId, previousStems, newStems);
    }

    /**
     * Reports what orphan cleanup removed, and warns when it removed more than the round wrote.
     *
     * <p>Deleting a scoped rule file is always worth a line in the build log — it is guardrails
     * leaving the repository — so every sweep emits a NOTE naming what went. The WARNING is
     * reserved for the shape that means something is wrong rather than something was deleted:
     * a round that removes <em>more</em> rules than it writes. Taking one annotation off a
     * twelve-class module writes eleven and removes one, and stays quiet; the {@code test-compile}
     * round of issue #330 wrote one and removed eleven, and would not have.
     *
     * @param scope    what the sweep covered, for the message (a directory or "the reactor root")
     * @param removed  qNames whose rules were removed
     * @param written  qNames this round wrote
     */
    public void orphanSweep(String scope, Collection<String> removed, Set<String> written) {
        if (removed.isEmpty()) {
            return;
        }
        List<String> lost = new ArrayList<>(removed);
        if (log != null) {
            log.info("granular.remove scope={} count={} written={} removed={}",
                scope, removed.size(), written.size(), removed);
        }
        if (removed.size() <= written.size()) {
            note("VibeTags: removed " + removed.size() + " orphaned scoped rule file(s) under "
                + scope + " (" + summarise(lost) + ").");
            return;
        }
        warn("VibeTags: removed " + removed.size() + " scoped rule file(s) under " + scope
                + " (" + summarise(lost) + ") while writing only " + written.size()
                + ". A round that deletes more guardrails than it produces is usually one that"
                + " could not see the sources they came from, not one whose annotations were"
                + " deleted — check this module's annotation processing before committing.",
            "granular.sweep scope={} removed={} written={} reason=removed-more-than-written",
            scope, removed, written.size());
    }

    private void note(String message) {
        if (messager != null) {
            messager.printMessage(Diagnostic.Kind.NOTE, message);
        }
    }

    private void warn(String message, String event, Object a, Object b, @Nullable Object c) {
        if (messager != null) {
            messager.printMessage(Diagnostic.Kind.WARNING, message);
        }
        if (log != null) {
            if (c != null) {
                log.warn(event, a, b, c);
            } else {
                log.warn(event, a, b);
            }
        }
    }

    /** Renders at most {@link #MAX_LISTED} names, then "and N more". */
    private static String summarise(List<String> names) {
        if (names.size() <= MAX_LISTED) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_LISTED)) + ", and " + (names.size() - MAX_LISTED) + " more";
    }
}
