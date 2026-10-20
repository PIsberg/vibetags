package com.acme.shop.payment;

/**
 * Transport to the acquirer's HTTP API. The production implementation lives in the
 * infrastructure module; tests use an in-memory fake.
 */
public interface AcquirerClient {

    ChargeResult submit(ChargeRequest request);

    ChargeResult reverse(String chargeId, long amountCents);
}
