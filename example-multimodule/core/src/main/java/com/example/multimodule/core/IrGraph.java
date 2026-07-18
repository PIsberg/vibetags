package com.example.multimodule.core;

import se.deversity.vibetags.annotations.AIImmutable;

import java.util.List;

/**
 * Immutable container of {@link IrNode}s.
 */
@AIImmutable
public final class IrGraph {

    private final List<IrNode> nodes;

    public IrGraph(List<IrNode> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    public List<IrNode> nodes() {
        return nodes;
    }
}
