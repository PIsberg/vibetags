package com.example.kotlin

import se.deversity.vibetags.annotations.AILocked

/**
 * Two classes in a file named after neither (issue #955). kapt's stub for [Invoice] is
 * Invoice.java, and a rule scoped to the guess Invoice.kt would load for no file at all. The rule
 * must name Billing.kt, which kapt records beside the stub and KSP reads directly; this example's
 * CI step compares the kapt and KSP rule files byte for byte.
 */
class Invoice(private val lines: List<InvoiceLine>) {

    @AILocked(reason = "BILLING-MULTI-CLASS: invoice totals are signed by the e-invoicing gateway, which rejects any change in rounding.")
    fun total(): Long = lines.sumOf { it.amount }
}

class InvoiceLine(val amount: Long)
