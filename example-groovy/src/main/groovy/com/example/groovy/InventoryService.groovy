package com.example.groovy

import se.deversity.vibetags.annotations.AIContext
import se.deversity.vibetags.annotations.AILocked

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

    @AILocked(reason = 'Reservation ordering is contract-tested against the warehouse ledger. Reordering double-allocates stock.')
    String reserve(String sku, int quantity) {
        return "reserved:${sku}:${quantity}"
    }

    String describe() {
        return 'InventoryService(groovy)'
    }
}
