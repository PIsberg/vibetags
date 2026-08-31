package com.example.enforcing;

import se.deversity.vibetags.annotations.AIPublicAPI;

/**
 * The surface partner plugins compile against. Enforcement covers the whole visible shape of the
 * type: adding, removing or retyping any public member drifts from the baseline and fails the
 * build until the change is approved with {@code -Dvibetags.baseline.update=true}.
 */
@AIPublicAPI(reason = "Partner billing plugins compile against this interface; a removed or "
    + "retyped member breaks them at their next build, not ours")
public interface RateCardApi {

    /** Resolves the rate card in force for a customer segment on a given day. */
    String rateCardFor(String segment, java.time.LocalDate effectiveDate);
}
