package com.example.gmm.app;

import com.example.gmm.core.IrNode;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;

/**
 * Entry point. Contextual rather than locked, so both bucket kinds appear across modules.
 *
 * <p>Also carries a safety-tier guardrail, which is what puts this module into the renderers that
 * emit safety families only. {@code .plandex.yaml} renders audit, locked and privacy and nothing
 * else, so a module whose every annotation is advisory is absent from it entirely: the CI check
 * that asserts each module survives the YAML merge needs a safety-tier witness per module or it
 * reports a merge failure that never happened.
 */
@AIContext(focus = "Wiring only: parsing and rendering live in their own modules",
           avoids = "Business logic")
@AIAudit(checkFor = {"Path Traversal"})
public class App {

    public static void main(String[] args) {
        System.out.println(new IrNode("root").name());
    }
}
