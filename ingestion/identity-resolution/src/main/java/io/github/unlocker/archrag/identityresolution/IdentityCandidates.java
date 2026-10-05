package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Хранилище признаков идентичности и кандидатов на совпадение.
 *
 * <p>Инварианты: {@code observe} идемпотентен (повтор, replay и rebuild не плодят кандидатов и не меняют
 * {@code created_at}); кандидат строится только на равенстве нормализованных значений; записи с общим
 * {@code gid} кандидатов не дают; {@code gid} не создаётся и не меняется.
 */
public interface IdentityCandidates {

  /** Ничего не сохраняет и кандидатов не возвращает. */
  IdentityCandidates DISABLED =
      new IdentityCandidates() {
        @Override
        public void observe(SourceKey key, UUID gid, NodeLabel label, Set<Feature> features) {}

        @Override
        public List<IdentityCandidate> candidatesOf(UUID gid) {
          return List.of();
        }
      };

  /**
   * Запоминает признаки записи и заводит или обновляет кандидатов среди других {@code gid} той же метки.
   *
   * @throws IdentityStoreException при отказе хранилища
   */
  void observe(SourceKey key, UUID gid, NodeLabel label, Set<Feature> features);

  /** Кандидаты, в паре которых участвует {@code gid}. */
  List<IdentityCandidate> candidatesOf(UUID gid);
}
