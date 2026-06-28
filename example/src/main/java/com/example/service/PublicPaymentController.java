package com.example.service;

import se.deversity.vibetags.annotations.AIPublicAPI;

/**
 * Demo controller class annotated with {@link AIPublicAPI}.
 * Tells the AI that this public API surface must remain backward-compatible.
 * Modifying the signature, return type, or exception list is strictly prohibited.
 */
@AIPublicAPI(reason = "Consumed by three external partner integrations pinned to v1; signature or return-shape changes are a breaking release and need a /v2 endpoint instead")
public class PublicPaymentController {
    
    public String executeExternalPayment(String paymentToken, double amount) {
        return "SUCCESS";
    }
}
