package com.example.service;

import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIPerformance;

import java.util.List;
import java.util.Map;

/**
 * Shared pricing service consumed by the checkout, cart, and promotions microservices.
 *
 * Method signatures are pinned by a versioned OpenAPI contract (v2). Any change
 * to names, parameter types, or return types is a breaking change that requires
 * a major-version bump and migration coordination across three teams.
 *
 * Internal pricing logic (algorithms, data-source integrations, caching strategies)
 * is freely modifiable — the AI is welcome to optimize it.
 */
@AIContext(
    focus = "Optimize pricing calculations for accuracy and throughput. Internal algorithms may use any efficient approach.",
    avoids = "Floating-point arithmetic for monetary values — use BigDecimal internally, but note that the contract-frozen signatures use double for backwards compatibility"
)
public class PricingService {

    /**
     * Calculate the final price for a product after all applicable rules are applied.
     *
     * Signature frozen: pinned by checkout-service v3.1 and mobile-app v2.4.
     * Internal algorithm may be replaced (rule engine, ML model, lookup table — all fine).
     */
    @AIContract(reason = "Signature locked by OpenAPI v2 contract. checkout-service and mobile-app bind to this exact signature. A type change is a breaking API change.")
    @AIPerformance(constraint = "Must complete in <5ms p99. Called on every cart update.")
    public double calculatePrice(String productId, int quantity, String customerId) {
        // Pricing engine — implementation can be freely changed
        return 0.0;
    }

    /**
     * Apply a promotional discount code and return the adjusted price.
     *
     * Signature frozen: shared with the promotions-service via message contract.
     * The discount strategy itself (percentage, BOGO, tiered) can be freely changed.
     */
    @AIContract(reason = "Promotions-service depends on this exact method signature for its async price-adjustment events. Changing parameter types would break the event deserialization.")
    public double applyPromoCode(String promoCode, double basePrice, String customerId) {
        // Discount strategy — implementation can be freely changed
        return basePrice;
    }

    /**
     * Return a bulk price matrix for a list of product/quantity pairs.
     *
     * Called by the B2B portal. Return type and structure pinned by portal contract.
     */
    @AIContract(reason = "B2B portal contract v1.2 — the List<Map<String,Object>> structure is serialized directly to JSON. Changing the return type breaks portal parsing.")
    public List<Map<String, Object>> getBulkPricing(List<String> productIds, int quantity) {
        // Bulk pricing logic — implementation can be freely changed
        return List.of();
    }

    /**
     * Check whether a given product is eligible for dynamic pricing.
     * Not part of any external contract — this method signature may be freely changed.
     */
    public boolean isDynamicPricingEligible(String productId) {
        return false;
    }
}
