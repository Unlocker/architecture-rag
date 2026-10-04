package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Соответствие {@code (source, sourceType, sourceId) -> gid} и approved crosswalk.
 *
 * <p>Инварианты: ключ получает {@code gid} один раз и навсегда (повторный вызов и rebuild графа
 * возвращают тот же {@code gid}); записи, связанные crosswalk, делят один {@code gid}. Единственный
 * источник {@code gid} для projector.
 */
public interface IdentityMapping {

  /** Возвращает {@code gid}, если ключ уже отображён; ничего не создаёт. */
  Optional<UUID> find(SourceKey key);

  /** Возвращает {@code gid} ключа, при отсутствии создаёт новый. Потокобезопасно: гонка даёт один {@code gid}. */
  UUID resolve(SourceKey key);

  /** Все ключи, отображённые на {@code gid}; для канонического узла из двух источников их два. */
  List<SourceKey> keysOf(UUID gid);

  /**
   * Применяет approved crosswalk: оба ключа получают общий {@code gid} (существующий у одного из них
   * или новый). Повторное применение идемпотентно.
   *
   * @return общий {@code gid}
   * @throws CrosswalkConflictException если ключи уже отображены на разные {@code gid}
   */
  UUID approve(Crosswalk crosswalk);
}
