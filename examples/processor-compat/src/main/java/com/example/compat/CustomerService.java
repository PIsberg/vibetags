package com.example.compat;

import org.mapstruct.factory.Mappers;

/**
 * Compiles only when both Lombok and MapStruct ran: it calls a Lombok accessor and uses the
 * mapper MapStruct generated.
 */
public class CustomerService {

    private final CustomerMapper mapper = Mappers.getMapper(CustomerMapper.class);

    public CustomerDto view(Customer customer) {
        if (customer.getId() == null) {
            throw new IllegalArgumentException("a customer needs an id");
        }
        return mapper.toDto(customer);
    }
}
