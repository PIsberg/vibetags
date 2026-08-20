package com.example.gmm.core;

import se.deversity.vibetags.annotations.AILocked;

/** Core node type. Locked so the generated files carry a safety bucket for this module. */
@AILocked(reason = "Core IR node shape is depended on by every downstream module")
public class IrNode {

    private final String name;

    public IrNode(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
