package com.example.kotlin

import se.deversity.vibetags.annotations.AICore
import se.deversity.vibetags.annotations.AIPrivacy

/**
 * A second annotated type so the generated files show more than one safety bucket:
 * the class lands in the privacy section, the lookup function in the core section.
 */
@AIPrivacy(reason = "Holds customer PII — never log, expose, or include field values in suggestions.")
class CustomerVault {

    @AICore(
        sensitivity = "critical",
        note = "Primary lookup path for every checkout; covered by the vault contract suite"
    )
    fun findCustomer(customerId: String): String? {
        return if (customerId.isBlank()) null else "customer:$customerId"
    }
}
