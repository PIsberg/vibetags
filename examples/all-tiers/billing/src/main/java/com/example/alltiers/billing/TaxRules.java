package com.example.alltiers.billing;

import java.math.BigDecimal;

import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * The rules that decide what tax a line attracts — the compliance and correctness guardrails.
 *
 * <p>Filed under the {@code domain-model} role, so its Tier-3 detail joins {@code LedgerEntry}'s in
 * {@code billing/.claude/rules/domain-model.md}. Two classes sharing one topic file is the point of
 * {@code .vibetags-roles}: an agent opening either one gets both rule sets.
 */
@AIArchitecture(belongsTo = "domain", cannotReference = {"com.example.alltiers.shipping", "javax.servlet"})
@AIDomainModel(allow = {"java.math.BigDecimal"})
@AIInternationalized(reason = "Tax names are shown on invoices in the customer's locale")
@AIRegulation(
    standard = "EU VAT Directive",
    clause = "Art. 98",
    description = "Reduced rates apply per member state and per product category")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE, note = "Rate tables are loaded once and never mutated")
@AITestDriven(
    coverageGoal = 100,
    framework = {AITestDriven.Framework.JUNIT_5},
    testLocation = "src/test/java/com/example/alltiers/billing",
    mockPolicy = "Use fixed rate tables; never call the live rate service from a unit test")
public final class TaxRules {

    private final BigDecimal standardRate;

    public TaxRules(BigDecimal standardRate) {
        this.standardRate = standardRate;
    }

    @AIPure(reason = "Callers memoize this on the assumption it is referentially transparent")
    @AIIdempotent(reason = "Applying the rate twice must equal applying it once for the same input")
    public BigDecimal applyStandardRate(BigDecimal net) {
        return net.multiply(standardRate);
    }

    @AIExplain(AIExplain.ComplexityLevel.HIGH)
    public BigDecimal reducedRateFor(String categoryCode, String memberState) {
        // Non-obvious on purpose: the reduced rate is the member state's own rate when it has one,
        // and otherwise the standard rate — but never below the Directive's 5% floor.
        BigDecimal candidate = "FOOD".equals(categoryCode)
            ? standardRate.movePointLeft(1)
            : standardRate;
        BigDecimal floor = new BigDecimal("0.05");
        return candidate.compareTo(floor) < 0 ? floor : candidate;
    }
}
