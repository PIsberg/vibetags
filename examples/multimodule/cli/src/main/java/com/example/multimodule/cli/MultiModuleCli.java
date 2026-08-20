package com.example.multimodule.cli;

import com.example.multimodule.core.IrGraph;
import com.example.multimodule.core.IrNode;
import com.example.multimodule.engine.LayoutEngine;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AITestDriven;

import java.util.List;

/**
 * Command-line entry point — carries the annotation set from the issue #278 repro so the
 * generated guardrail files demonstrably contain entries from ALL reactor modules, not just
 * the last one compiled.
 */
@AIAudit(checkFor = {"Path Traversal"})
@AITestDriven(testLocation = "src/test/java/com/example/multimodule/cli")
public class MultiModuleCli {

    @AIContract(reason = "Public CLI surface; flags are documented downstream")
    public int run(String[] args) {
        IrGraph graph = new IrGraph(List.of(new IrNode("n1", "start")));
        System.out.println(new LayoutEngine().layout(graph));
        return 0;
    }

    @AIPure
    @AIIdempotent(reason = "Derives output path from inputs only")
    static String outputPath(String baseDir, String name) {
        return baseDir + "/" + name + ".svg";
    }
}
