package com.example.scala;

import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AILocked;

/**
 * The working half of this example: Java sources in a mixed Scala/Java module are
 * compiled by javac, so the processor runs over them normally. In a Scala codebase,
 * guardrails live on Java types like this one.
 */
@AIPrivacy(reason = "Audit entries carry user identifiers — never log or expose field values.")
public final class AuditLog {

    private AuditLog() {
    }

    @AILocked(reason = "Append-only contract with the compliance archive. Rewriting entries violates retention rules.")
    public static String append(String userId, String action) {
        return "audit:" + userId + ":" + action;
    }
}
