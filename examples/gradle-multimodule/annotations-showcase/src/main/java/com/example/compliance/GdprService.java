package com.example.compliance;

import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AIIdempotent;

/**
 * GDPR compliance service.
 *
 * <p>Demonstrates {@code @AIRegulation} — ties code to a specific compliance clause so
 * AI assistants document compliance impact for any change and never weaken the
 * requirement.
 */
@AIRegulation(
    standard = "GDPR",
    clause = "Art. 17",
    description = "Right to erasure — when invoked, deletes ALL PII for the given user across every connected store."
)
public class GdprService {

    @AIIdempotent(reason = "Deleting a user's data multiple times must produce the same result as deleting once — must not throw on second invocation.")
    public void deleteAllUserData(String userId) {
        // Implementation cascades the deletion to user, sessions, orders, audit logs, etc.
    }

    @AIRegulation(
        standard = "GDPR",
        clause = "Art. 20",
        description = "Right to data portability — exports the user's data in a machine-readable format."
    )
    public byte[] exportUserData(String userId) {
        return new byte[0];
    }
}
