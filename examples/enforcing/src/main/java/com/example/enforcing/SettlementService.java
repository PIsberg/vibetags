package com.example.enforcing;

import se.deversity.vibetags.annotations.AIContract;

/** Runs the nightly settlement against the clearing house. */
public final class SettlementService {

    @AIContract(reason = "The clearing-house SDK reflects on this signature by name and parameter "
        + "types; a rename or retype is only discovered at the nightly run, in production")
    public String settle(String batchId, long amountMinorUnits) throws IllegalStateException {
        return batchId + ":" + amountMinorUnits;
    }
}
