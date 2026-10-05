package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Подозрение, что две записи одного семейства меток описывают один объект. Только запись: узлы не
 * объединяются, {@code gid} не меняются. Пара нормализована ({@code left < right}); {@code matched} —
 * совпавшие признаки с нормализованными значениями.
 */
public record IdentityCandidate(
    SourceKey left,
    SourceKey right,
    NodeLabel labelFamily,
    List<Feature> matched,
    BigDecimal score,
    String status,
    Instant firstSeenAt,
    Instant updatedAt) {}
