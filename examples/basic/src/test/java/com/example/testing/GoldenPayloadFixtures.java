package com.example.testing;

import se.deversity.vibetags.annotations.AILocked;

/**
 * Safety-tier guardrail on test code, which routing must leave where it is.
 *
 * <p>{@code TESTING.md} is loaded on demand. An agent that reads it has already decided to work on
 * tests, and by then a lock like this one has had its chance to matter. The six safety annotations
 * therefore stay in the always-loaded files whatever source set they sit in, and this class is the
 * committed proof: its reason appears in {@code CLAUDE.md}, not in {@code TESTING.md}, while
 * {@link PaymentFixturesTest} in the same source set goes the other way.
 */
@AILocked(
    reason = "These byte sequences are the partner's recorded settlement responses. Regenerating "
        + "or reformatting them makes the suite agree with itself and disagree with production."
)
public final class GoldenPayloadFixtures {

    private GoldenPayloadFixtures() {
    }
}
