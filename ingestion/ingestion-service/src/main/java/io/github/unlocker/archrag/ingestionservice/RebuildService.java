package io.github.unlocker.archrag.ingestionservice;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Component;

/**
 * Полный rebuild графа: удаляет проекцию в Neo4j и переигрывает весь журнал ({@link ReplayService}).
 *
 * <p>Инварианты: constraints и индексы схемы не трогаются; {@code identity_mapping}, crosswalk и checkpoint'ы
 * адаптеров не трогаются, поэтому {@code gid} прежние; {@code replayId} всегда новый (повтор со старым replayId
 * ничего не применил бы: его строки уже в конечном статусе). Если replay прервался, граф неполон, и операцию нужно
 * повторить целиком. Вызывается под {@link AdminLock}.
 */
@Component
public class RebuildService {

  /** Удаление пачками: один запрос на всё сразу не помещается в память транзакции. */
  static final String WIPE = "MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS";

  private final Driver driver;
  private final ReplayService replay;

  public RebuildService(Driver driver, ReplayService replay) {
    this.driver = driver;
    this.replay = replay;
  }

  /**
   * Сносит проекцию и переигрывает все исходные события журнала всех источников.
   *
   * @param replayId новый идентификатор прогона (уникальный; не использованный ранее)
   */
  public ReplayResult rebuild(String replayId) {
    // CALL IN TRANSACTIONS допустим только в неявной транзакции, поэтому session.run, а не executeWrite.
    try (var session = driver.session()) {
      session.run(WIPE).consume();
    }
    return replay.replay(null, null, null, replayId);
  }
}
