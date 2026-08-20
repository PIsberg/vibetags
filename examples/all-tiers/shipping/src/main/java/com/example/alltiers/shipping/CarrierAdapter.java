package com.example.alltiers.shipping;

import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * The extension point every carrier plugs into.
 *
 * <p>{@code @AIExtensible} has to sit on a type that can actually be extended — the processor
 * rejects it on a {@code final} class with "nothing can extend it, so the STRATEGY_PATTERN route
 * the annotation asks for is not open", which is worth seeing in an example.
 */
@AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE,
    note = "Adapters are looked up from a ConcurrentHashMap; never wrap the lookup in synchronized")
@AIParallelTests(reason = "Each adapter test binds its own mock carrier port and shares no state")
public class CarrierAdapter {

    @AIMemoryBudget(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION)
    public int estimateGrams(int lengthMm, int widthMm, int heightMm) {
        return (lengthMm * widthMm * heightMm) / 6000;
    }

    @AIPure(reason = "Used inside the rate cache key; a side effect here would poison every cached rate")
    public static String cacheKey(String carrier, String service) {
        return carrier + '/' + service;
    }
}
