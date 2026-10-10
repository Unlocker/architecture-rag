package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * Идемпотентная миграция живого графа (ADR 0028): структурные связи, ставшие темпоральными, но записанные до этого
 * без {@code validFrom}, получают {@code validFrom = coalesce(validFrom, firstSeenAt записи-источника)}.
 *
 * <p>Инварианты: затрагиваются только связи с {@code validFrom IS NULL}, поэтому повторный запуск ничего не меняет;
 * {@code validTo} и {@code assertedBy*} не трогаются; внутри транзакции только Neo4j. {@code firstSeenAt} берётся
 * минимумом по узлам, которые утверждает запись-источник связи; у записи без узла (или без {@code firstSeenAt})
 * берётся время миграции. Rebuild из журнала даёт {@code validFrom} от времени события и миграции не требует.
 * Тип связи подставляется из enum {@link RelationType}.
 */
public final class TemporalRelationMigration {

  /** Типы, переведённые в темпоральные ADR 0028. */
  static final Set<RelationType> TYPES =
      EnumSet.of(
          RelationType.HAS_DEPLOYMENT,
          RelationType.IN_ENVIRONMENT,
          RelationType.DECOMPOSED_INTO,
          RelationType.IMPLEMENTED_IN);

  private final Driver driver;

  public TemporalRelationMigration(Driver driver) {
    this.driver = Objects.requireNonNull(driver, "driver");
  }

  /**
   * Заполняет {@code validFrom} там, где его нет.
   *
   * @param now время миграции — запасное значение, если {@code firstSeenAt} недоступен
   * @return число обновлённых связей
   */
  public long migrate(Instant now) {
    long updated = 0;
    for (RelationType type : TYPES) {
      try (Session session = driver.session()) {
        updated +=
            session.executeWrite(
                tx ->
                    tx.run(
                            "MATCH ()-[rel:" + type.name() + "]->() WHERE rel.validFrom IS NULL "
                                + "OPTIONAL MATCH (r:SourceRecord {source: rel.assertedBySource, "
                                + "sourceType: rel.assertedByType, sourceId: rel.assertedById})-[:ASSERTS]->(n) "
                                + "WITH rel, min(n.firstSeenAt) AS first "
                                + "SET rel.validFrom = coalesce(first, $now) "
                                + "RETURN count(rel) AS c",
                            Map.of("now", NodeProperties.utc(now)))
                        .single()
                        .get("c")
                        .asLong());
      }
    }
    return updated;
  }
}
