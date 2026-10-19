package com.acme.shop.payment;

import se.deversity.vibetags.annotations.AILocked;

/**
 * Client for the card acquirer: authorises charges and issues refunds.
 */
@AILocked(reason = "The acquirer's nightly settlement file is matched against these exact method signatures and integer minor units (contract ACQ-2291); any change here breaks reconciliation, so route changes through the payments team.")
public class PaymentGateway {

    private final AcquirerClient client;

    public PaymentGateway(AcquirerClient client) {
        this.client = client;
    }

    public ChargeResult charge(long amountCents, String currency, String cardToken) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive, was " + amountCents);
        }
        return client.submit(new ChargeRequest(amountCents, currency, cardToken));
    }

    public ChargeResult refund(String chargeId, long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive, was " + amountCents);
        }
        return client.reverse(chargeId, amountCents);
    }
}
