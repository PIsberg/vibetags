package com.example.enforcing;

import se.deversity.vibetags.annotations.AILocked;

/** Computes the tariff a settlement run charges. */
public final class TariffEngine {

    @AILocked(reason = "Recorded invoices replay through this exact signature during dispute "
        + "resolution; a changed parameter list makes historical disputes non-reproducible")
    public long computeTariff(String rateCard, long consumedUnits) {
        return consumedUnits * (long) rateCard.length();
    }

    /** Unguarded on purpose: this one may change shape freely without touching the baseline. */
    public String describe() {
        return "TariffEngine(enforcing-example)";
    }
}
