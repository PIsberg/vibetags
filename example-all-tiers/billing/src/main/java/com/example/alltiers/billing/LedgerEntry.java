package com.example.alltiers.billing;

import java.math.BigDecimal;

import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AIStrictTypes;

/**
 * A second topic in the same module, so Tier 3 has more than one file to group.
 *
 * <p>The roles file files this under {@code domain-model}, which is why its rules land in
 * {@code billing/.claude/rules/domain-model.md} alongside anything else matching that glob —
 * the layout Claude's own docs recommend, rather than one file per class.
 */
@AICore(sensitivity = "critical", note = "Every monetary total in the system is derived from these rows")
@AIImmutable(note = "Shared across the reconciliation threads without copying")
@AISchemaSafe(reason = "Persisted to the ledger table replicated to the finance warehouse")
@AIStrictTypes(reason = "Money is BigDecimal here and must never widen to double or Object")
public final class LedgerEntry {

    @AILoadBearing(
        invariant = "Entries are appended in sequence order and never renumbered",
        breaksIf = "A caller reuses a sequence, which silently merges two entries in the warehouse")
    private final long sequence;

    private final BigDecimal amount;

    public LedgerEntry(long sequence, BigDecimal amount) {
        this.sequence = sequence;
        this.amount = amount;
    }

    public long sequence() {
        return sequence;
    }

    public BigDecimal amount() {
        return amount;
    }
}
