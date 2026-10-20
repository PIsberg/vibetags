package com.example.compat;

import lombok.Data;

/**
 * What the REST layer returns for a customer.
 */
@Data
public class CustomerDto {

    private String id;

    private String email;
}
