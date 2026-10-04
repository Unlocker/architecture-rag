package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.command.CloseAssertion;
import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Одно изменение объекта источника для проекции: команды нормализатора плюс разрешённые {@code gid}.
 *
 * <p>Инварианты (нарушение — {@link IllegalArgumentException}): не более одного узла-команды суммарно
 * ({@code UpsertNode} либо {@code TombstoneSourceRecord}); запись только со связями и пустой список
 * допустимы; ключ узла-команды равен {@code key}, а версия {@code SourceRecord} равна {@code version};
 * все связи и закрытия утверждаются этой же записью; для узла записи и обоих концов каждой связи есть
 * {@code gid}. {@code gid} выдаёт только {@code IdentityMapping}: проектор сам их не создаёт.
 *
 * @param key запись источника, к которой относится изменение
 * @param version версия объекта в источнике
 * @param eventTime время события (UTC); задаёт {@code lastSeenAt}, {@code validFrom} по умолчанию, {@code deletedAt}
 * @param syncRunId прогон синхронизации или {@code null}
 * @param gids {@code gid} по ключам источника
 */
public record ProjectionRequest(
    SourceKey key,
    SourceVersion version,
    Instant eventTime,
    String syncRunId,
    Map<SourceKey, UUID> gids,
    List<GraphCommand> commands) {

  public ProjectionRequest {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(eventTime, "eventTime");
    gids = Map.copyOf(gids);
    commands = List.copyOf(commands);
    long upserts = commands.stream().filter(UpsertNode.class::isInstance).count();
    long tombstones = commands.stream().filter(TombstoneSourceRecord.class::isInstance).count();
    if (upserts + tombstones > 1) {
      throw new IllegalArgumentException("at most one UpsertNode or TombstoneSourceRecord is allowed");
    }
    for (GraphCommand command : commands) {
      switch (command) {
        case UpsertNode u -> {
          requireSame(key, u.record().key(), "record key");
          if (!u.record().sourceVersion().equals(version.value())) {
            throw new IllegalArgumentException("record sourceVersion differs from request version");
          }
          requireGid(gids, u.record().key());
        }
        case TombstoneSourceRecord t -> requireSame(key, t.key(), "tombstone key");
        case UpsertRelation r -> {
          requireSame(key, r.assertedBy(), "relation assertedBy");
          requireGid(gids, r.from());
          requireGid(gids, r.to());
        }
        case CloseAssertion c -> {
          requireSame(key, c.assertedBy(), "close assertedBy");
          requireGid(gids, c.from());
          requireGid(gids, c.to());
        }
      }
    }
    if (tombstones == 1 && commands.size() != 1) {
      throw new IllegalArgumentException("a tombstone request must not carry other commands");
    }
  }

  /** {@code true}, если запрос — удаление записи. */
  public boolean isTombstone() {
    return commands.stream().anyMatch(TombstoneSourceRecord.class::isInstance);
  }

  /** {@code true}, если в запросе нет ни узла, ни tombstone: запись состоит только из связей (или пуста). */
  public boolean isRelationOnly() {
    return commands.stream().noneMatch(c -> c instanceof UpsertNode || c instanceof TombstoneSourceRecord);
  }

  private static void requireSame(SourceKey key, SourceKey other, String what) {
    if (!key.equals(other)) {
      throw new IllegalArgumentException(what + " differs from request key");
    }
  }

  private static void requireGid(Map<SourceKey, UUID> gids, SourceKey endpoint) {
    if (!gids.containsKey(endpoint)) {
      throw new IllegalArgumentException("gid is missing for a referenced source key");
    }
  }
}
