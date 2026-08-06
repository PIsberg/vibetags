package com.example.multimodule.engine;

import com.example.multimodule.core.IrGraph;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * Pluggable layout engine operating on the core IR.
 */
@AIExtensible
@AIThreadSafe(note = "Stateless; safe to share across render threads")
public class LayoutEngine {

    public String layout(IrGraph graph) {
        return "laid out " + graph.nodes().size() + " nodes";
    }
}
