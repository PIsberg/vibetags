package com.example.gmm.platform;

import se.deversity.vibetags.annotations.AIFeatureFlag;

/** Runtime feature gating. Second unrouted class, so the per-class path is covered more than once. */
@AIFeatureFlag(flag = "reactor.parallel-render", defaultValue = false)
public class FeatureGate {

    public boolean enabled(String flag) {
        return false;
    }
}
