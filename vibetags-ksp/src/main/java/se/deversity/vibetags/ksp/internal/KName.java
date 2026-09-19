package se.deversity.vibetags.ksp.internal;

import javax.lang.model.element.Name;

/** A {@link Name} over a plain string. javac's names compare by content, and so does this. */
final class KName implements Name {

    private final String value;

    KName(String value) {
        this.value = value;
    }

    @Override
    public boolean contentEquals(CharSequence cs) {
        return value.contentEquals(cs);
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KName name && name.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
