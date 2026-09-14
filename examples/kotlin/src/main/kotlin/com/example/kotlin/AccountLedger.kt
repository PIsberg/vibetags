package com.example.kotlin

import se.deversity.vibetags.annotations.AILocked

/**
 * A value class: on the JVM an [AccountId] is its underlying String, so every function that
 * takes or returns one gets a mangled JVM name (balanceFor-XXXXXXX).
 */
@JvmInline
value class AccountId(val raw: String)

/**
 * Pins two kapt behaviours this example's CI step asserts (issue #681). All three guardrails are
 * deliberate; see docs/JVM-LANGUAGES.md, Kotlin.
 *
 * - [balanceFor] takes a value class, so kapt leaves it out of the Java stub. Its @AILocked reaches
 *   no processor and must appear in NO generated file. If it ever does, kapt started emitting
 *   mangled functions and the docs are out of date.
 * - [settle] takes kotlin.Result, which is also a value class but is not mangled: it must appear,
 *   as settle(java.lang.Object).
 * - [reconcile] is internal, so its JVM name carries the Kotlin module name, and so does its path.
 */
class AccountLedger {

    @AILocked(reason = "LEDGER-VALUE-CLASS-LOST: balance lookup is reconciled nightly against the bank feed.")
    fun balanceFor(account: AccountId): Long {
        return account.raw.length.toLong()
    }

    @AILocked(reason = "LEDGER-RESULT-KEPT: settlement outcome mapping is part of the payout contract.")
    fun settle(outcome: Result<Long>): String {
        return outcome.fold({ "settled:$it" }, { "failed" })
    }

    @AILocked(reason = "LEDGER-INTERNAL-KEPT: reconciliation batches are replayed by the audit job.")
    internal fun reconcile(batch: String): Int {
        return batch.length
    }
}
