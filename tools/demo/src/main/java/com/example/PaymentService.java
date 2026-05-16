package com.example;

import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPrivacy;

@AILocked(reason = "PCI-DSS scope — do not modify without a security review")
public class PaymentService {

    @AIPrivacy(reason = "Card data — never log or expose in suggestions")
    private final String cardToken;

    public PaymentService(String cardToken) {
        this.cardToken = cardToken;
    }

    @AIContract(reason = "Bound by OpenAPI v2 contract with mobile-app and checkout-service")
    public String charge(double amount, String currency) {
        return "tx-" + System.currentTimeMillis();
    }
}
