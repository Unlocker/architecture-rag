package io.github.unlocker.archrag.sourcestubs;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Хранилище объектов заглушки EAM в формате {@code ArchObject}: тип -> id -> объект. {@code ver} растёт при каждом
 * изменении {@code attrs} (допущение «не подтверждено»); повторная запись тех же {@code attrs} — no-op. Архивированный
 * объект исчезает из основного списка (допущение). Потокобезопасно.
 */
public final class EamApiStore {

  private final Clock clock;
  private final Map<String, TreeMap<Long, Entry>> byType = new LinkedHashMap<>();

  public EamApiStore(Clock clock) {
    this.clock = clock;
  }

  /** Создаёт объект ({@code ver=1}) либо обновляет {@code attrs}, повышая {@code ver}; без изменений ничего не делает. */
  public synchronized void upsert(String type, long id, Map<String, Object> attrs) {
    TreeMap<Long, Entry> objects = byType.computeIfAbsent(type, t -> new TreeMap<>());
    Instant now = clock.instant();
    Entry old = objects.get(id);
    if (old == null) {
      objects.put(id, new Entry(id, 1, now, now, new LinkedHashMap<>(attrs)));
    } else if (!old.attrs().equals(attrs)) {
      objects.put(id, new Entry(id, old.ver() + 1, old.ctime(), now, new LinkedHashMap<>(attrs)));
    }
  }

  /** Архивирует объект: он пропадает из основного списка и из {@code GET /{id}/}. */
  public synchronized void archive(String type, long id) {
    TreeMap<Long, Entry> objects = byType.get(type);
    if (objects != null) {
      objects.remove(id);
    }
  }

  synchronized boolean hasType(String type) {
    return byType.containsKey(type);
  }

  synchronized Optional<Entry> find(String type, long id) {
    return Optional.ofNullable(byType.getOrDefault(type, new TreeMap<>()).get(id));
  }

  /** Страница списка: сортировка по списку ключей ({@code id}, {@code ctime}, {@code mtime}, поля attrs; {@code -} — убывание). */
  synchronized List<Entry> page(String type, String sort, int page, int pageSize) {
    List<Entry> all = new ArrayList<>(byType.getOrDefault(type, new TreeMap<>()).values());
    Comparator<Entry> order = Comparator.comparingLong(Entry::id);
    if (sort != null && !sort.isBlank()) {
      Comparator<Entry> chain = null;
      for (String key : sort.split(",")) {
        boolean desc = key.startsWith("-");
        Comparator<Entry> c = comparator(desc ? key.substring(1) : key.trim());
        chain = chain == null ? (desc ? c.reversed() : c) : chain.thenComparing(desc ? c.reversed() : c);
      }
      order = chain.thenComparingLong(Entry::id);
    }
    all.sort(order);
    int from = (int) Math.min((long) (page - 1) * pageSize, all.size());
    return all.subList(from, Math.min(from + pageSize, all.size()));
  }

  private static Comparator<Entry> comparator(String key) {
    return switch (key) {
      case "id" -> Comparator.comparingLong(Entry::id);
      case "ctime" -> Comparator.comparing(Entry::ctime);
      case "mtime" -> Comparator.comparing(Entry::mtime);
      default -> Comparator.comparing(e -> String.valueOf(e.attrs().get(key)));
    };
  }

  /** Объект в хранилище. */
  record Entry(long id, long ver, Instant ctime, Instant mtime, Map<String, Object> attrs) {}
}
