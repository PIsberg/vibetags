package com.example.compat;

import org.mapstruct.Mapper;
import se.deversity.vibetags.annotations.AIContract;

/**
 * MapStruct writes the implementation, reading the accessors Lombok generated.
 */
@Mapper
public interface CustomerMapper {

    @AIContract(reason = "The REST layer and two batch jobs call this signature.")
    CustomerDto toDto(Customer customer);
}
