package com.example.gmm.tests;

import com.example.gmm.core.IrNode;

/**
 * Stands in for the reactor's test suite. Carries no annotations on purpose: this module's rules
 * are mirrored in from its siblings.
 */
public class ReactorSmokeTest {

    public boolean nodeKeepsItsName() {
        return "root".equals(new IrNode("root").name());
    }
}
