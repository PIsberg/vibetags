package com.example.indexed.app;

import com.example.indexed.core.DocumentModel;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Application service. Its guardrails live in {@code app/.claude/rules/services.md} (Tier 3);
 * the reactor-root {@code CLAUDE.md} only points at this module (Tier 1 index), while
 * {@code GEMINI.md} still embeds the full merged block for tools without a scoped-rules feature.
 */
@AIAudit(checkFor = {"Path Traversal", "Insecure Deserialization"})
@AITestDriven(testLocation = "src/test/java/com/example/indexed/app")
public class DocumentService {

    @AIContract(reason = "Public service surface consumed across module boundaries")
    public String render(DocumentModel doc) {
        return storageKey(doc.id(), doc.title());
    }

    @AIPure
    @AIIdempotent(reason = "Derives the storage key from inputs only")
    static String storageKey(String id, String title) {
        return id + "/" + title.strip().replace(' ', '-');
    }
}
