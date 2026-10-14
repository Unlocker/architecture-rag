package io.github.unlocker.archrag.graphprojector;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Перенос записи на другой {@code gid} (подтверждённый кандидат на совпадение): прикладывается к
 * {@link ProjectionRequest} и меняет проекцию так, что запись утверждает узел {@code into}, а не {@code from}.
 *
 * <p>Инварианты: {@code from != into}; проверка версии для такого запроса обходится явно (равная версия иначе дала бы
 * {@code NOOP_SAME_VERSION}); открытые связи записи у узла {@code from} закрываются на {@code at}; при
 * {@code retireSource} узел {@code from} закрывается тем же путём, что и при tombstone, и получает
 * {@code mergedInto = into}, а все его открытые связи закрываются.
 *
 * @param from прежний {@code gid} записи
 * @param into {@code gid} выжившего узла (равен {@code gid} записи в запросе)
 * @param retireSource на {@code from} не осталось ключей: узел подлежит закрытию
 * @param at момент закрытия прежних узла и связей (UTC); не время исходного события
 */
public record Relocation(UUID from, UUID into, boolean retireSource, Instant at) {

  public Relocation {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(into, "into");
    Objects.requireNonNull(at, "at");
    if (from.equals(into)) {
      throw new IllegalArgumentException("relocation must change the gid");
    }
  }
}
