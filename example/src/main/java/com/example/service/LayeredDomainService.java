package com.example.service;

import se.deversity.vibetags.annotations.AIArchitecture;

/**
 * Demo class annotated with {@link AIArchitecture}.
 * Restricts package imports to enforce architectural layering and clean boundaries.
 * In this example, the domain layer must not reference infrastructure or UI layers.
 */
@AIArchitecture(belongsTo = "domain", cannotReference = {"infrastructure", "ui"})
public class LayeredDomainService {
    
    public void processCoreDomainLogic() {
        System.out.println("Processing clean domain logic...");
    }
}
