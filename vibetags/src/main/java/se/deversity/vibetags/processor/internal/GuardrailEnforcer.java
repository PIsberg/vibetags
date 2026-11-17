package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opt-in enforcing mode: fails the build when a guarded element's shape has changed since it was
 * last approved (<a href="https://github.com/PIsberg/vibetags/issues/284">issue #284</a>).
 *
 * <p>VibeTags guardrails are advisory by design — they go into the agent's context to make a mistake
 * less likely, and the real gate in a consuming project is checkstyle/PMD/SpotBugs. That is the
 * right default, but it means an {@code @AIContract} lowers the probability of a breaking change
 * rather than preventing one. For the guardrails that are cheap to verify statically, this turns
 * "the AI was told not to" into "the build will not let it".
 *
 * <p><strong>It enforces only what it can prove.</strong> Three families are checked, all by
 * comparing {@link ElementSignature} against the committed {@link EnforcementBaseline}:
 *
 * <ul>
 *   <li>{@code @AILocked} — the element's visible shape must not change;</li>
 *   <li>{@code @AIContract} — likewise, which is exactly what the annotation promises callers;</li>
 *   <li>{@code @AIPublicAPI} — likewise, for the published surface.</li>
 * </ul>
 *
 * <p>{@code @AICallersOnly} and {@code @AIStrictClasspath} are <em>not</em> enforced, and that is a
 * deliberate boundary rather than an omission: proving them needs call-graph and method-body
 * analysis that an annotation processor cannot do portably (the Tree API is unavailable under
 * Gradle — see {@link ModuleRootResolver}). Guardrails whose meaning is semantic
 * ({@code @AIThreadSafe}'s strategy, {@code @AITestDriven}'s coverage intent) stay advisory by
 * nature. Enforcing a subset honestly beats enforcing everything unreliably.
 *
 * <p>Nothing here runs unless {@code -Avibetags.enforce} names a family, and it never writes a file
 * except under {@code -Avibetags.baseline.update=true}.
 */
public final class GuardrailEnforcer {

    /** Family name → the annotation whose elements it guards. */
    private static final Map<String, Class<? extends Annotation>> FAMILIES = Map.of(
        "locked", AILocked.class,
        "contract", AIContract.class,
        "publicapi", AIPublicAPI.class);

    /** Selects every enforceable family. */
    public static final String ALL = "all";

    private final Messager messager;
    private final @Nullable Logger log;

    public GuardrailEnforcer(Messager messager, @Nullable Logger log) {
        this.messager = messager;
        this.log = log;
    }

    /**
     * Parses the {@code -Avibetags.enforce} value into family names, warning about any it does not
     * recognise. Returns an empty set when the option is absent or blank — enforcement off.
     */
    public Set<String> parseFamilies(@Nullable String option) {
        Set<String> selected = new LinkedHashSet<>();
        if (option == null || option.isBlank()) {
            return selected;
        }
        // Explicit -1 limit: String.split(String) silently drops trailing empty fields, so
        // "contract," and "contract" parse the same by accident rather than by decision. The empty
        // entries are skipped below on purpose, which is the behaviour a generated -A argument needs.
        for (String raw : option.split(",", -1)) {
            String family = raw.trim().toLowerCase(Locale.ROOT);
            if (family.isEmpty()) {
                continue;
            }
            if (ALL.equals(family)) {
                selected.addAll(FAMILIES.keySet());
            } else if (FAMILIES.containsKey(family)) {
                selected.add(family);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: -Avibetags.enforce names an unknown guardrail family '" + family
                        + "'. Enforceable families are " + sorted(FAMILIES.keySet())
                        + " (or 'all'). Families whose meaning cannot be proved statically —"
                        + " @AICallersOnly, @AIStrictClasspath, @AIThreadSafe, @AITestDriven —"
                        + " stay advisory and are not enforceable.");
            }
        }
        return selected;
    }

    /**
     * Compares this compilation's guarded elements against the baseline.
     *
     * @param update when true, rewrite the baseline from what was compiled instead of checking it
     * @return the number of violations reported (always 0 when {@code update})
     */
    public int enforce(GuardrailModel model, Set<String> families, Path root, String moduleId, boolean update) {
        if (families.isEmpty()) {
            return 0;
        }
        Guarded current = guardedElements(model, families);
        Optional<ReadBaseline> read = readBaseline(root, moduleId);
        if (read.isEmpty()) {
            return 1; // reported: an unreadable baseline fails the check rather than passing it
        }
        EnforcementBaseline baseline = read.get().baseline();
        if (update) {
            recordBaseline(baseline, root, moduleId, families, current);
            return 0;
        }
        if (!read.get().recorded() || baseline.hasNothingFor(moduleId)) {
            warnNothingRecorded(moduleId);
            return 0;
        }
        int violations = reportChangedShapes(current, baseline, moduleId);
        violations += reportVanishedElements(current, baseline, moduleId, families);
        reportResult(moduleId, families, current, violations);
        return violations;
    }

    /**
     * This compilation's guarded elements under {@code families}: their signatures and the elements
     * themselves, both keyed by {@link EnforcementBaseline#familyAndPath}, in family-name order.
     */
    private record Guarded(Map<String, String> signatures, Map<String, TaggedElement> elements) {
    }

    /** The baseline as read, and whether the file carries a format header this processor knows. */
    private record ReadBaseline(EnforcementBaseline baseline, boolean recorded) {
    }

    private static Guarded guardedElements(GuardrailModel model, Set<String> families) {
        Map<String, String> signatures = new LinkedHashMap<>();
        Map<String, TaggedElement> elements = new LinkedHashMap<>();
        for (String family : sortedList(families)) {
            Class<? extends Annotation> annotation = FAMILIES.get(family);
            if (annotation == null) {
                continue; // parseFamilies only ever admits known names, so this is unreachable
            }
            for (TaggedElement element : model.of(annotation)) {
                if (element.signature().isEmpty()) {
                    continue; // no provable shape (a package, or an element javac did not model)
                }
                String path = element.kind() == ElementTag.CONSTRUCTOR
                    ? EnforcementBaseline.constructorPath(element.path())
                    : element.path();
                String key = EnforcementBaseline.familyAndPath(family, path);
                signatures.put(key, element.signature());
                elements.put(key, element);
            }
        }
        return new Guarded(signatures, elements);
    }

    /** The baseline at {@code root}, or empty after reporting that it could not be read. */
    private Optional<ReadBaseline> readBaseline(Path root, String moduleId) {
        try {
            return Optional.of(new ReadBaseline(EnforcementBaseline.load(root), EnforcementBaseline.exists(root)));
        } catch (IOException e) {
            // Present but unreadable is not "nothing recorded". Reading it as empty fell through to
            // the warning below and a pass: one Cp1252 byte from a Windows editor switched the gate
            // off, and the build stayed green.
            messager.printMessage(Diagnostic.Kind.ERROR,
                "VibeTags: could not read " + EnforcementBaseline.FILE_NAME + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); enforcement did not run."
                    + " Fix the file, or delete it and record it again with -Avibetags.baseline.update=true.");
            if (log != null) {
                log.error("enforce.skip module={} reason=baseline-unreadable error={}", moduleId, e.toString());
            }
            return Optional.empty();
        }
    }

    private void recordBaseline(EnforcementBaseline baseline, Path root, String moduleId, Set<String> families,
                                Guarded current) {
        try {
            baseline.update(root, moduleId, families, current.signatures());
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: enforcement baseline updated for module '" + moduleId + "' ("
                    + current.signatures().size() + " guarded element(s)). Commit "
                    + EnforcementBaseline.FILE_NAME + ".");
            if (log != null) {
                log.info("enforce.baseline.update module={} elements={}", moduleId, current.signatures().size());
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "VibeTags: could not write " + EnforcementBaseline.FILE_NAME + ": " + e.getMessage());
        }
    }

    private void warnNothingRecorded(String moduleId) {
        // Enforcing against a baseline that was never recorded would fail every build on the
        // day the option is switched on, which teaches people to switch it off again.
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: -Avibetags.enforce is on but " + EnforcementBaseline.FILE_NAME
                + " records nothing for module '" + moduleId + "'. Nothing was checked."
                + " Run once with -Avibetags.baseline.update=true and commit the result.");
    }

    /**
     * Direction 1: an approved element whose shape changed in place, a type gaining or losing a
     * public member, say, where the element's own path is unaffected.
     *
     * @return the number of changed elements reported
     */
    private int reportChangedShapes(Guarded current, EnforcementBaseline baseline, String moduleId) {
        int violations = 0;
        for (Map.Entry<String, String> entry : current.signatures().entrySet()) {
            String[] parts = entry.getKey().split("\t", 2);
            String approved = baseline.signatureFor(moduleId, parts[0], parts[1]);
            if (approved == null) {
                continue; // newly annotated element: not yet approved, but not a violation either
            }
            if (approved.equals(entry.getValue())) {
                continue;
            }
            violations++;
            TaggedElement changed = current.elements().get(entry.getKey());
            if (changed == null) {
                continue; // signatures and elements are filled in lockstep; unreachable in practice
            }
            reportChanged(parts[0], changed, approved, entry.getValue());
        }
        return violations;
    }

    /**
     * Direction 2: an approved element that is simply no longer there. This is the common one: a
     * method's path embeds its parameter types, so changing {@code charge(String,double)} to
     * {@code charge(String,long)} abandons the approved entry rather than editing it. Renaming,
     * deleting, or dropping the annotation all land here too, and all of them are things a
     * contract-frozen signature promised would not happen without review.
     *
     * @return the number of vanished elements reported
     */
    private int reportVanishedElements(Guarded current, EnforcementBaseline baseline, String moduleId,
                                       Set<String> families) {
        int violations = 0;
        for (String familyAndPath : baseline.approvedFor(moduleId, families)) {
            if (current.signatures().containsKey(familyAndPath)) {
                continue;
            }
            violations++;
            String[] parts = familyAndPath.split("\t", 2);
            reportMissing(parts[0], parts[1]);
        }
        return violations;
    }

    private void reportResult(String moduleId, Set<String> families, Guarded current, int violations) {
        if (log != null) {
            log.info("enforce.result module={} families={} checked={} violations={}",
                moduleId, families, current.signatures().size(), violations);
        }
        if (violations == 0) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: enforcement passed — " + current.signatures().size() + " guarded element(s) unchanged ("
                    + sorted(families) + ").");
        }
    }

    private void reportChanged(String family, TaggedElement element, String approved, String actual) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "VibeTags: @" + annotationName(family) + " violation — the shape of " + element.path()
                + " changed from what " + EnforcementBaseline.FILE_NAME + " approved."
                + "\n  approved: " + approved
                + "\n  now:      " + actual
                + "\n" + HOW_TO_APPROVE);
        if (log != null) {
            log.error("enforce.violation family={} element={} approved={} actual={}",
                family, element.path(), approved, actual);
        }
    }

    private void reportMissing(String family, String path) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "VibeTags: @" + annotationName(family) + " violation — " + path
                + " was approved in " + EnforcementBaseline.FILE_NAME
                + " but this compilation has no such guarded element. It was renamed, deleted, had"
                + " its signature changed, or lost its annotation."
                + "\n" + HOW_TO_APPROVE);
        if (log != null) {
            log.error("enforce.violation family={} element={} reason=absent", family, path);
        }
    }

    private static final String HOW_TO_APPROVE =
        "If the change is intended, run once with -Avibetags.baseline.update=true and commit the new"
        + " baseline, so the change is reviewed rather than assumed.";

    private static String annotationName(String family) {
        Class<? extends Annotation> type = FAMILIES.get(family);
        return type == null ? family : type.getSimpleName();
    }

    private static String sorted(Set<String> names) {
        return String.join(", ", sortedList(names));
    }

    private static List<String> sortedList(Set<String> names) {
        return names.stream().sorted().toList();
    }
}
