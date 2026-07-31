package se.deversity.vibetags.processor.internal.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import se.deversity.vibetags.processor.model.TaggedElement;

/**
 * The guardrail stanzas rendered for one granular-rule owner (a class or a package), kept
 * <em>structured</em> rather than pre-flattened into text.
 *
 * <p>Structure is what lets a whole rule file collapse the constant rule sentence that would
 * otherwise repeat once per annotated element (issue #313): {@link GranularSections} groups the
 * stanzas by section title and hoists the lines every stanza in a section shares. Assembling the
 * body as opaque text — as this type's {@code StringBuilder} predecessor did — makes that
 * impossible without re-parsing generated markdown.
 *
 * <p>Implements {@link CharSequence} and renders lazily in {@link #toString()}, so it substitutes
 * directly wherever the accumulated body used to be a {@code StringBuilder}. The rendered form is
 * byte-identical to the old output whenever no section reaches
 * {@link GranularSections#MIN_GROUP_SIZE} stanzas with shared lines.
 */
public final class GranularBody implements CharSequence {

    /**
     * One rendered stanza: the element it describes, the section it belongs to, and its body
     * lines (the markdown between the heading and the next stanza, already fully formatted).
     */
    public record Entry(TaggedElement owner, TaggedElement element, String title, List<String> lines) {

        /** Defensive copy so a stanza can never be mutated after it has been recorded. */
        public Entry {
            lines = List.copyOf(lines);
        }

        /** True when the annotation sat on the owning type/package itself rather than a member. */
        public boolean ownerLevel() {
            return owner.equals(element);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Memoized render; invalidated by {@link #add}. Volatile so that a body rendered on one thread
     * and read on another (the processor swaps writers around a parallel write phase) can never
     * publish a partially-constructed String — recomputation is idempotent, so a lost update is
     * harmless but an unsafe publication would not be.
     */
    private volatile String rendered;

    /** Records one stanza. Stanzas are rendered in insertion order within their section. */
    public void add(Entry entry) {
        entries.add(entry);
        rendered = null;
    }

    /** The recorded stanzas, in insertion order — used for cross-owner grouping in role files. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** True when no stanza was recorded for this owner. */
    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        String r = rendered;
        if (r == null) {
            r = GranularSections.render(entries, false);
            rendered = r;
        }
        return r;
    }

    @Override
    public int length() {
        return toString().length();
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().subSequence(start, end);
    }
}
