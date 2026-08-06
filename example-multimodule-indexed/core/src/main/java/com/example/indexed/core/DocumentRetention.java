package com.example.indexed.core;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;

/**
 * Retention rules for stored documents.
 *
 * <p>Carries the compliance and lifecycle guardrails. {@code @AIPrivacy} and {@code @AIIgnore} are
 * safety-tier and stay inline in the reactor-root index; the rest are verbose-tier and collapse to
 * the module pointer.
 */
@AIContext(
    focus = "Retention windows and the legal basis for them",
    avoids = "Do not shorten a window to make a test pass; the windows are set by the standards named below")
@AIRegulation(standard = "GDPR", clause = "Art. 5(1)(e)",
    description = "Storage limitation: documents are kept no longer than the stated purpose requires")
@AIInternationalized(reason = "Retention notices are shown to users in their own locale; no concatenated sentences")
public final class DocumentRetention {

    @AIPrivacy(reason = "Owner email identifies a natural person; never log it or put it in a suggestion")
    @AISecureLogging(AISecureLogging.MaskingPolicy.HASH)
    private final String ownerEmail;

    @AIIgnore(reason = "Cached derived value with no meaning outside this instance")
    private transient long cachedExpiryEpochDay;

    public DocumentRetention(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    @AIExplain(AIExplain.ComplexityLevel.HIGH)
    public long expiryEpochDay(long createdEpochDay, int retentionDays) {
        // Deliberately non-obvious: retention runs from the end of the calendar year of creation,
        // which is what the standard means by "the purpose is exhausted".
        long endOfYear = createdEpochDay - (createdEpochDay % 365) + 365;
        cachedExpiryEpochDay = endOfYear + retentionDays;
        return cachedExpiryEpochDay;
    }

    @AIDeprecated(
        replacedBy = "expiryEpochDay(long, int)",
        migrationGuide = "Pass the creation day explicitly instead of relying on the system clock",
        deadline = "2027-01-01")
    @AISunset(jira = "DOC-4471")
    public long expiryFromNow(int retentionDays) {
        return expiryEpochDay(0L, retentionDays);
    }

    @AITemporary(
        expiresOn = "2026-12-31",
        reason = "Bridges the pre-2026 records that stored a retention class instead of a day count")
    public int legacyRetentionDays(String retentionClass) {
        return "long".equals(retentionClass) ? 3650 : 365;
    }
}
