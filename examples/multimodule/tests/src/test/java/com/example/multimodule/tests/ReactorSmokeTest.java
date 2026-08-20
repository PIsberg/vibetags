package com.example.multimodule.tests;

import com.example.multimodule.cli.MultiModuleCli;
import com.example.multimodule.core.IrNode;
import com.example.multimodule.engine.LayoutEngine;

/**
 * Stands in for the reactor's real test suite: it exercises the annotated classes from core,
 * engine and cli while carrying no {@code @AI*} annotations of its own.
 *
 * <p>That is exactly the layout issue #312 describes. Without the {@code .vibetags-mirror} opt-in
 * in this module, an assistant editing this file would see no guardrails at all for the locked and
 * privacy-sensitive code it touches — the rules would sit in sibling modules, which an ancestor
 * walk never reaches. With it, {@code tests/.claude/rules/mirrored-*.md} carry them here.
 */
public final class ReactorSmokeTest {

    private ReactorSmokeTest() {
    }

    public static void main(String[] args) {
        System.out.println(IrNode.class.getSimpleName()
            + " / " + LayoutEngine.class.getSimpleName()
            + " / " + MultiModuleCli.class.getSimpleName());
    }
}
