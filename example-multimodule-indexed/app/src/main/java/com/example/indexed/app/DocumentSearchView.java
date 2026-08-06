package com.example.indexed.app;

import com.example.indexed.core.DocumentIndexEntry;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIThreadAffinity;

/**
 * Read model for search results.
 *
 * <p>The counterpart of {@code DocumentIndexEntry}'s {@code @AIKeepInSync}: these two must gain
 * fields together, and the annotation on each names the other. {@code @AISecure} is safety-tier and
 * stays inline in the reactor-root index; the rest collapse to the module pointer.
 */
@AIKeepInSync(
    mirrors = {"com.example.indexed.core.DocumentIndexEntry"},
    reason = "Projects the index entry field for field; a field added there and not here is invisible to search",
    enforcedBy = "DocumentIndexEntryTest#projectionCoversEveryField")
@AIPublicAPI(reason = "Returned from the public search endpoint; the field names are the wire format")
@AISecure(aspect = "Query handling")
@AIStrictClasspath(reason = "Serialized by the platform's own Jackson; adding a second JSON library changes the output")
@AIStrictExceptions(reason = "Search failures must surface as SearchException, never a raw runtime type")
public class DocumentSearchView {

    private final String documentId;
    private final String title;

    public DocumentSearchView(DocumentIndexEntry entry) {
        this.documentId = entry.documentId();
        this.title = entry.title();
    }

    public String documentId() {
        return documentId;
    }

    public String title() {
        return title;
    }

    @AIBannedApi(
        forbidden = {"java.lang.String.format", "java.util.Date"},
        useInstead = "StringBuilder and java.time",
        reason = "Runs per result row; String.format dominates the profile at this call rate")
    @AIMemoryBudget(AIMemoryBudget.AllocationPolicy.NO_AUTOBOXING)
    @AIObservability(
        metrics = {"search.render.count"},
        traces = {"search.render"},
        logs = {"search.render.slow"},
        note = "Renaming a metric breaks the search dashboard and its alerts")
    public String renderRow(@AIInputSanitized(AIInputSanitized.SanitizerType.XSS) String highlight) {
        return documentId + " " + title + " " + highlight;
    }

    @AICallersOnly({"com.example.indexed.app.DocumentService"})
    @AIThreadAffinity(
        value = AIThreadAffinity.Affinity.NEVER_MAIN,
        thread = "search-worker",
        marshalVia = "SearchExecutor.submit",
        symptomIfViolated = "The UI thread blocks on index I/O and the app stops painting")
    String loadFromIndex(long sequence) {
        return documentId + "@" + sequence;
    }
}
