package com.example.indexed.app;

import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AISandboxOnly;

/**
 * Bulk import of documents from the previous system.
 *
 * <p>Carries the work-in-progress guardrails — the ones that say "this is not finished", "this is
 * behind a flag", "do not hand-edit this". None of them are safety-tier, so in the reactor-root
 * index they are exactly what the module pointer replaces.
 */
@AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)
@AIPrototype(reason = "Shape of the import pipeline is still being decided; do not build on these types")
@AIParallelTests(reason = "Each import runs against its own temporary directory and shares no state")
public class DocumentImportJob {

    @AIGenerated(
        from = "schema/legacy-import.yaml",
        regenerateWith = "mvn generate-sources",
        editInstead = "schema/legacy-import.yaml")
    static final String[] LEGACY_COLUMNS = {"doc_id", "doc_title", "created_ts"};

    @AIFeatureFlag(flag = "import.v2.enabled", defaultValue = false)
    public boolean useV2Pipeline() {
        return false;
    }

    @AIDraft(instructions = "Implement resumable import: checkpoint every 1000 rows and restart from the last checkpoint")
    public int importAll(String sourcePath) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @AILegacyBridge(reason = "Translates the pre-2020 column names; deleted once the last tenant is migrated")
    String mapLegacyColumn(String column) {
        return "doc_id".equals(column) ? "documentId" : column;
    }

    @AISandboxOnly(reason = "Writes directly to the index without validation; catastrophic against production data")
    void reindexEverything() {
        throw new UnsupportedOperationException("sandbox only");
    }
}
