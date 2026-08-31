package com.example.groovy

import se.deversity.vibetags.annotations.AIContext
import se.deversity.vibetags.annotations.AILocked
import se.deversity.vibetags.annotations.AIPrivacy

/**
 * Demonstrates VibeTags on Groovy sources. Joint compilation generates Java stubs for
 * Groovy classes, and with {@code javaAnnotationProcessing = true} the annotation
 * processor runs over those stubs — so plain Java annotations with SOURCE retention
 * work on Groovy classes and methods unchanged.
 */
@AIContext(
    focus = 'Reserves and releases warehouse stock; the ledger is the source of truth',
    avoids = 'Caching stock counts — reservations race, the ledger query is already indexed'
)
class InventoryService {

    // DELIBERATELY LOST: groovyc's Java stubs carry no fields at all, so this field-level
    // guardrail generates NOTHING. CI asserts its absence from the output; `vibetags doctor`
    // is the tool that reports it. In real Groovy code, put the guardrail on the accessor or
    // the class instead. See docs/JVM-LANGUAGES.md ("What Groovy silently loses").
    @AIPrivacy(reason = 'Warehouse contact addresses are personal data under the carrier DPA')
    String contactEmail

    @AILocked(reason = 'Reservation ordering is contract-tested against the warehouse ledger. Reordering double-allocates stock.')
    String reserve(String sku, int quantity) {
        return "reserved:${sku}:${quantity}"
    }

    String describe() {
        return 'InventoryService(groovy)'
    }
}
