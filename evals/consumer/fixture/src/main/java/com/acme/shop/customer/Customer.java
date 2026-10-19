package com.acme.shop.customer;

import se.deversity.vibetags.annotations.AIPrivacy;

/**
 * A registered shop customer.
 */
public class Customer {

    private final String id;
    private final String displayName;

    @AIPrivacy(reason = "Personal data under GDPR Art. 4: must never reach logs, metrics, traces or error messages. Identify a customer by id instead.")
    private final String email;

    @AIPrivacy(reason = "National tax identifier: must never reach logs, metrics, traces or error messages. Identify a customer by id instead.")
    private final String taxId;

    public Customer(String id, String displayName, String email, String taxId) {
        this.id = id;
        this.displayName = displayName;
        this.email = email;
        this.taxId = taxId;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getTaxId() {
        return taxId;
    }

    @Override
    public String toString() {
        return "Customer[id=" + id + ", displayName=" + displayName + ", email=" + email + ", taxId=" + taxId + "]";
    }
}
