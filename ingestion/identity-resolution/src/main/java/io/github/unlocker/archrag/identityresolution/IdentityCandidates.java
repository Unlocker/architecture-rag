package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Set;

/**
 * Хранилище признаков идентичности и кандидатов на совпадение.
 *
 * <p>Инварианты: {@code record} идемпотентен (повтор, replay и rebuild не плодят кандидатов, не меняют
 * {@code first_seen_at} и {@code status}); кандидат строится только на равенстве нормализованных значений
 * и хотя бы одном сильном признаке; записи с общим {@code gid} кандидатов не дают; {@code gid} здесь не
 * создаётся и не меняется.
 */
public interface IdentityCandidates {

  /**
   * Заменяет признаки ключа, находит совпадения среди других ключей того же семейства меток и upsert-ит
   * кандидатов.
   *
   * @return кандидаты, затронутые этим вызовом
   * @throws IdentityStoreException при отказе хранилища
   */
  List<IdentityCandidate> record(SourceKey key, NodeLabel label, Set<Feature> features);

  /** Удаляет признаки ключа (после tombstone); сами кандидаты остаются как история. */
  void forget(SourceKey key);

  /** Кандидаты, в паре которых участвует {@code key}. */
  List<IdentityCandidate> candidatesOf(SourceKey key);
}
