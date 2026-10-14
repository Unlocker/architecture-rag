package io.github.unlocker.archrag.canonicalmodel.provenance;

import java.util.Locale;
import java.util.Optional;

/**
 * Source systems of the PoC; {@link #MANUAL} is for manual moderation and crosswalk.
 *
 * <p>Invariants: {@link #code()} is the lower-case enum name; {@link #urn()} is {@code urn:corp:<code>}
 * and is the {@code source} value of events, dedup keys and checkpoints.
 */
public enum SourceSystemCode {
    EAM, SCM, CMDB, DEPLOYMAP, MANUAL;

    /** Prefix of the {@code source} URN. */
    public static final String URN_PREFIX = "urn:corp:";

    private final String code = name().toLowerCase(Locale.ROOT);

    /** Lower-case short code ({@code eam}). */
    public String code() {
        return code;
    }

    /** Event {@code source} value: {@code urn:corp:<code>}. */
    public String urn() {
        return URN_PREFIX + code;
    }

    /**
     * Parses {@code urn:corp:<code>}; empty for {@code null}, a foreign prefix or an unknown code.
     * {@link #MANUAL} is returned as any other value: the caller decides whether it is acceptable.
     */
    public static Optional<SourceSystemCode> fromUrn(String urn) {
        if (urn == null || !urn.startsWith(URN_PREFIX)) {
            return Optional.empty();
        }
        String tail = urn.substring(URN_PREFIX.length());
        for (SourceSystemCode value : values()) {
            if (value.code.equals(tail)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
