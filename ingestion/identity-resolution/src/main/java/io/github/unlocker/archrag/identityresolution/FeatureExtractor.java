package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Снимает признаки идентичности с команд одного события.
 *
 * <p>Чистая функция: карту {@code gids} только читает (не изменяет и не создаёт {@code gid}),
 * {@code IdentityMapping} не вызывает. Если среди команд нет {@link UpsertNode} для ключа (запись только
 * со связью), признаков нет.
 */
public final class FeatureExtractor {

  private FeatureExtractor() {}

  /** Метка {@link UpsertNode} события для {@code key}, если он есть. */
  public static Optional<NodeLabel> labelOf(SourceKey key, List<GraphCommand> commands) {
    return upsertOf(key, commands).map(u -> u.data().label());
  }

  /**
   * Признаки узла события.
   *
   * @param gids уже разрешённая карта {@code gid}; {@code OWNER} берётся из неё по концу связи {@code OWNED_BY}
   */
  public static Set<Feature> extract(SourceKey key, List<GraphCommand> commands, Map<SourceKey, UUID> gids) {
    Set<Feature> features = new LinkedHashSet<>();
    Optional<UpsertNode> own = upsertOf(key, commands);
    if (own.isEmpty()) {
      return features;
    }
    NodeData data = own.get().data();
    switch (data) {
      case ComputeInstance c -> add(features, FeatureKind.HOSTNAME, FeatureNormalizer.hostname(c.hostname()));
      case Repository r -> add(features, FeatureKind.REPOSITORY_URL, FeatureNormalizer.repositoryUrl(r.url()));
      case ITSystem s -> add(features, FeatureKind.NAME, FeatureNormalizer.name(s.name()));
      case Service s -> add(features, FeatureKind.NAME, FeatureNormalizer.name(s.name()));
      case Team t -> add(features, FeatureKind.NAME, FeatureNormalizer.name(t.name()));
      default -> {}
    }
    if (data instanceof ITSystem || data instanceof Service) {
      commands.stream()
          .filter(UpsertRelation.class::isInstance)
          .map(UpsertRelation.class::cast)
          .filter(r -> r.type() == RelationType.OWNED_BY && r.from().equals(key))
          .map(r -> gids.get(r.to()))
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .ifPresent(gid -> features.add(new Feature(FeatureKind.OWNER, gid.toString())));
    }
    return features;
  }

  private static Optional<UpsertNode> upsertOf(SourceKey key, List<GraphCommand> commands) {
    return commands.stream()
        .filter(UpsertNode.class::isInstance)
        .map(UpsertNode.class::cast)
        .filter(u -> u.record().key().equals(key))
        .findFirst();
  }

  private static void add(Set<Feature> features, FeatureKind kind, Optional<String> value) {
    value.ifPresent(v -> features.add(new Feature(kind, v)));
  }
}
