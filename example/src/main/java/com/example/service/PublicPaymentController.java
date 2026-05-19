package com.example.service;

import se.deversity.vibetags.annotations.AIPublicAPI;

/**
 * Demo controller class annotated with {@link AIPublicAPI}.
 * Tells the AI that this public API surface must remain backward-compatible.
 * Modifying the signature, return type, or exception list is strictly prohibited.
 */
@AIPublicAPI
public class PublicPaymentController {
    
    public String executeExternalPayment(String paymentToken, double amount) {
        return "SUCCESS";
    }
}
