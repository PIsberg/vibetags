package com.acme.shop.customer;

import java.util.Optional;

/**
 * Persistence for customers.
 */
public interface CustomerRepository {

    Optional<Customer> findByEmail(String email);

    void save(Customer customer);
}
