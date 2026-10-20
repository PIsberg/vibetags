package com.example.compat;

import lombok.Data;
import se.deversity.vibetags.annotations.AIPrivacy;

/**
 * A customer record whose accessors Lombok writes.
 */
@Data
public class Customer {

    private String id;

    @AIPrivacy(reason = "Personal data under GDPR: never log it or copy it into a fixture.")
    private String email;
}
