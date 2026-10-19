package com.acme.shop.customer;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Registers new customers.
 */
public class CustomerService {

    private static final Logger LOG = System.getLogger(CustomerService.class.getName());

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    public Customer register(Customer customer) {
        if (customer.getEmail() == null || !customer.getEmail().contains("@")) {
            throw new IllegalArgumentException("a valid email address is required");
        }
        if (repository.findByEmail(customer.getEmail()).isPresent()) {
            throw new IllegalStateException("an account with this email address already exists");
        }
        repository.save(customer);
        LOG.log(Level.INFO, "registered customer {0}", customer.getId());
        return customer;
    }
}
