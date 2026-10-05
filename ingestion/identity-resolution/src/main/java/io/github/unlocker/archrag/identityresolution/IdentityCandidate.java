package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Подозрение, что два canonical-узла одной метки описывают один объект. Только запись: узлы не
 * объединяются и {@code gid} не меняются. Пара нормализована ({@code leftGid < rightGid}).
 */
public record IdentityCandidate(
    UUID leftGid,
    UUID rightGid,
    NodeLabel label,
    Set<FeatureType> features,
    BigDecimal score,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
