package com.example.indexed.core;

import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;

/**
 * Immutable core domain type. Its verbose guardrails live in
 * {@code core/.claude/rules/domain-model.md} (Tier 3) and load only when this file is opened;
 * the reactor-root {@code CLAUDE.md} carries just a one-line pointer to this module (Tier 1 index).
 */
@AIDomainModel
@AIImmutable(note = "Shared across threads without copies; every field is final")
@AILocked(reason = "Core document model: structural changes ripple through every module")
public final class DocumentModel {

    private final String id;
    private final String title;

    public DocumentModel(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }
}
