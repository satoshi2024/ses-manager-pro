package com.ses.importcenter.foundation;

/** 列の不在、明示null、値を区別するcanonical field。 */
public record FieldValue<T>(Presence presence, T value) {
    public enum Presence { NOT_PROVIDED, EXPLICIT_NULL, VALUE }
    public FieldValue {
        if (presence == null || (presence == Presence.VALUE) != (value != null)) {
            throw new IllegalArgumentException("field presenceとvalueが一致しません");
        }
    }
    public static <T> FieldValue<T> missing() { return new FieldValue<>(Presence.NOT_PROVIDED, null); }
    public static <T> FieldValue<T> explicitNull() { return new FieldValue<>(Presence.EXPLICIT_NULL, null); }
    public static <T> FieldValue<T> of(T value) { return new FieldValue<>(Presence.VALUE, java.util.Objects.requireNonNull(value)); }
}
