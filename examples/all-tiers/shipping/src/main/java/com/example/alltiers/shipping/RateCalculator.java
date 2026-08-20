package com.example.alltiers.shipping;

import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AISandboxOnly;

/**
 * Work in progress, annotated as such.
 *
 * <p>{@code @AIDraft} is the one annotation that asks the agent to <em>write</em> something rather
 * than leave it alone, and {@code @AIPrototype} is the one that relaxes the rules instead of
 * tightening them — worth having both in an example that is otherwise all constraints.
 */
@AIPrototype(reason = "Spike for the Q3 carrier-rate evaluation; no error handling on purpose")
public class RateCalculator {

    @AIGenerated(
        from = "schema/carrier-rates.yaml",
        regenerateWith = "mvn generate-sources",
        editInstead = "schema/carrier-rates.yaml")
    static final String[] RATE_COLUMNS = {"carrier", "zone", "grams", "price"};

    @AIDraft(instructions = "Implement zone lookup from the rate table, falling back to zone 9 for "
        + "unknown postcodes, and cache the result per (carrier, zone) pair")
    public int zoneFor(String postcode) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @AIIdempotent(reason = "Re-quoting the same shipment must return the same price, not a new one")
    public int quote(String carrier, int grams) {
        return grams / 100 + carrier.length();
    }

    @AISandboxOnly(reason = "Talks to the carrier's test endpoint with seeded credentials")
    void primeTestRates() {
        throw new UnsupportedOperationException("sandbox only");
    }
}
