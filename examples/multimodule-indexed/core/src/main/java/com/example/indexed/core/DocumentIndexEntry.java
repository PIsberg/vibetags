package com.example.indexed.core;

import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * One entry in the document index.
 *
 * <p>Carries the structural guardrails — the ones about what this type may depend on, what its
 * shape means on disk, and what must change alongside it. In the reactor root these collapse to a
 * pointer at this
 * module; the detail lives in {@code core/.claude/rules/} and loads when this file is opened.
 * {@code @AICore} is the exception: it is a safety-tier annotation, so it stays inline in the root
 * index where an agent sees it before it opens anything.
 */
@AIArchitecture(belongsTo = "domain", cannotReference = {"com.example.indexed.app", "javax.servlet"})
@AICore(sensitivity = "high", note = "Index entries are read by every module; a field change is a format change")
@AISchemaSafe(reason = "Persisted to the document store; field order and names are the on-disk format")
@AIStrictTypes(reason = "Identifiers are typed to stop a title being passed where an id belongs")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE, note = "Every field is final; share freely")
public final class DocumentIndexEntry {

    @AILoadBearing(
        invariant = "Sort order of the index depends on this being monotonically increasing",
        breaksIf = "A caller assigns a sequence lower than one already issued")
    private final long sequence;

    @AIKeepInSync(
        mirrors = {"com.example.indexed.app.DocumentSearchView"},
        reason = "The search view projects these fields verbatim; adding one here without adding it there hides it from search",
        enforcedBy = "DocumentIndexEntryTest#projectionCoversEveryField")
    private final String documentId;

    private final String title;

    public DocumentIndexEntry(long sequence, String documentId, String title) {
        this.sequence = sequence;
        this.documentId = documentId;
        this.title = title;
    }

    public long sequence() {
        return sequence;
    }

    public String documentId() {
        return documentId;
    }

    public String title() {
        return title;
    }

    @AIPerformance(constraint = "O(1). Called once per document per query; anything that allocates shows up in the p99")
    public int compareBySequence(DocumentIndexEntry other) {
        return Long.compare(sequence, other.sequence);
    }
}
