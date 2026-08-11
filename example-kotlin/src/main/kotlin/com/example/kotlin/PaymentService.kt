package com.example.kotlin

import se.deversity.vibetags.annotations.AIContext
import se.deversity.vibetags.annotations.AILocked

/**
 * Demonstrates VibeTags on Kotlin sources via kapt. The annotations are plain Java
 * annotations with SOURCE retention, so they work on Kotlin classes and functions
 * exactly as they do on Java members — and never reach the compiled class file.
 */
@AIContext(
    focus = "Coordinates payment authorization and capture against the gateway",
    avoids = "Retry logic — the gateway client already retries; a second layer double-charges"
)
class PaymentService {

    @AILocked(reason = "Charge idempotency key derivation is contract-tested against the gateway. Changing it re-charges in-flight payments.")
    fun chargeKey(orderId: String, attempt: Int): String {
        return "$orderId:$attempt"
    }

    fun describe(): String {
        return "PaymentService(kotlin)"
    }
}
