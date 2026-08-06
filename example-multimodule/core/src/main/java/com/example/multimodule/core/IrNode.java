package com.example.multimodule.core;

import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AILocked;

/**
 * Intermediate-representation node — the core data model every other module builds on.
 */
@AIDomainModel
@AILocked(reason = "Core IR node: structural changes break every downstream module")
public class IrNode {

    private final String id;
    private final String label;

    public IrNode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
