package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.model.TransitiveRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The text and the order of inherited rules: what the renderers print for a rule's attributes,
 * what {@code BuildFingerprint} hashes for them, and the one order every list of inherited rules is
 * sorted into.
 *
 * <p>Kept out of {@link TransitiveRule} so the model holds data and nothing else. One function
 * serves both the renderers and the fingerprint, so the two can never disagree about what counts
 * as a change.
 */
public final class TransitiveRules {

    /**
     * Total order by package, then annotation, then origin, then attributes.
     *
     * <p>Load-bearing for the same reason {@code GuardrailModelBuilder} sorts its buckets: manifests
     * are discovered in whatever order the compilation's imports happened to be walked, and that
     * order differs between Maven and Gradle and between machines. Sorting by this makes the
     * generated files, and the build fingerprint, a function of the dependency set alone.
     */
    public static final Comparator<TransitiveRule> ORDER = TransitiveRules::compare;

    private TransitiveRules() {}

    /**
     * Inherited rules after the advisory cap: the rules kept, in {@link #ORDER}, and how many
     * advisory ones the cap dropped.
     */
    public record Capped(List<TransitiveRule> kept, int dropped) {

        public Capped {
            kept = List.copyOf(kept);
        }
    }

    /**
     * {@code rules} sorted into {@link #ORDER}, keeping every safety rule and the first
     * {@code maxAdvisory} advisory ones; {@code 0} or less is no cap.
     *
     * <p>Sorted before the cap, not after: which advisory rules survive must depend on the
     * dependency set alone, not on the order the manifests happened to be discovered or listed in.
     * A cap that could drop a safety rule would be a security feature that quietly stops working on
     * large projects, so the safety tier never counts against it.
     */
    public static Capped capped(Collection<TransitiveRule> rules, int maxAdvisory) {
        List<TransitiveRule> sorted = new ArrayList<>(rules);
        sorted.sort(ORDER);
        if (maxAdvisory <= 0) {
            return new Capped(sorted, 0);
        }
        List<TransitiveRule> kept = new ArrayList<>(sorted.size());
        int advisoryKept = 0;
        for (TransitiveRule rule : sorted) {
            boolean safety = rule.tier() == TransitiveRule.Tier.SAFETY;
            if (safety || advisoryKept < maxAdvisory) {
                kept.add(rule);
                advisoryKept += safety ? 0 : 1;
            }
        }
        return new Capped(kept, sorted.size() - kept.size());
    }

    /** The rule's attributes as one {@code key=value} line, or {@code ""} when it has none. */
    public static String memberSummary(TransitiveRule rule) {
        Map<String, String> members = rule.members();
        if (members.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static int compare(TransitiveRule a, TransitiveRule b) {
        int c = a.packageName().compareTo(b.packageName());
        if (c != 0) {
            return c;
        }
        c = a.annotation().compareTo(b.annotation());
        if (c != 0) {
            return c;
        }
        c = a.origin().compareTo(b.origin());
        if (c != 0) {
            return c;
        }
        return memberSummary(a).compareTo(memberSummary(b));
    }
}
