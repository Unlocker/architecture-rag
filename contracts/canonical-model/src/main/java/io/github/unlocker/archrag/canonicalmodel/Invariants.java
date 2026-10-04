package io.github.unlocker.archrag.canonicalmodel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared constructor checks for canonical-model records.
 *
 * <p>Convention: {@code null} gives {@link NullPointerException}, a violated rule gives
 * {@link IllegalArgumentException}.
 */
public final class Invariants {

    private Invariants() {
    }

    /** Returns the value if it is neither {@code null} nor blank. */
    public static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Returns {@code null} or a non-blank value; an empty string is rejected. */
    public static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when set");
        }
        return value;
    }

    /** Requires {@code from <= to}. */
    public static void requireNotAfter(Instant from, Instant to, String fromName, String toName) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(fromName + " must not be after " + toName);
        }
    }

    /**
     * Validates and copies a property map. Allowed values: {@link String}, {@link Boolean},
     * {@link Long}, {@link Integer}, {@link Double}, {@link Instant} and {@link List} of those.
     * The result is immutable.
     */
    public static Map<String, Object> copyProperties(Map<String, Object> properties) {
        Objects.requireNonNull(properties, "properties");
        properties.forEach((key, value) -> {
            requireText(key, "property key");
            checkValue(key, value, true);
        });
        var copy = new java.util.HashMap<String, Object>();
        properties.forEach((key, value) -> copy.put(key, value instanceof List<?> list ? List.copyOf(list) : value));
        return Map.copyOf(copy);
    }

    private static void checkValue(String key, Object value, boolean listAllowed) {
        Objects.requireNonNull(value, "property " + key);
        if (value instanceof String || value instanceof Boolean || value instanceof Long
                || value instanceof Integer || value instanceof Double || value instanceof Instant) {
            return;
        }
        if (listAllowed && value instanceof List<?> list) {
            for (Object element : new ArrayList<>(list)) {
                checkValue(key, element, false);
            }
            return;
        }
        throw new IllegalArgumentException(
                "property " + key + " has unsupported type " + value.getClass().getName());
    }
}
