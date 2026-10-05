package io.github.unlocker.archrag.integrationtests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.neo4j.driver.Driver;

/**
 * Канонический дамп графа для сравнения «до и после» (rebuild, повторная доставка): канонические узлы с их
 * доменными связями, а также {@code SourceRecord} с {@code ASSERTS}. Поля из {@link #EXCLUDED_KEYS}, {@code elementId},
 * {@code SyncRun} и {@code PROCESSED} в дамп не входят. Используется только {@link SyncAcceptanceIT}.
 */
final class CanonicalGraph {

  /**
   * Временные поля, которые вправе отличаться между прогонами. Сейчас список пуст: rebuild воспроизводит и их, а
   * сравнение ослаблять без решения архитектора нельзя.
   */
  static final Set<String> EXCLUDED_KEYS = Set.of();

  private static final String CANONICAL =
      "(n:ITSystem OR n:Service OR n:Repository OR n:Team OR n:Environment OR n:Deployment OR n:ComputeInstance)";

  private CanonicalGraph() {}

  /** Отсортированные строки: канонические узлы, доменные связи между ними, {@code SourceRecord} и {@code ASSERTS}. */
  static List<String> dump(Driver driver) {
    List<String> out = new ArrayList<>();
    for (var r : driver.executableQuery("MATCH (n) WHERE " + CANONICAL + " RETURN labels(n) AS l, properties(n) AS p")
        .execute().records()) {
      out.add("N " + sorted(r.get("l").asList()) + " " + stable(r.get("p").asMap()));
    }
    for (var r : driver.executableQuery("MATCH (r:SourceRecord) RETURN properties(r) AS p").execute().records()) {
      out.add("S " + stable(r.get("p").asMap()));
    }
    // Типы связей зафиксированы списком; обход без переменной длины.
    for (var r : driver.executableQuery(
        "MATCH (a)-[r:DECOMPOSED_INTO|IMPLEMENTED_IN|HAS_DEPLOYMENT|IN_ENVIRONMENT|RUNS_ON|PART_OF|HOSTED_ON|DEPENDS_ON|OWNED_BY|ASSERTS]->(b) "
            + "WHERE NOT a:SyncRun RETURN labels(a) AS al, properties(a) AS ap, type(r) AS t, properties(r) AS rp, "
            + "labels(b) AS bl, properties(b) AS bp").execute().records()) {
      out.add("R " + sorted(r.get("al").asList()) + stable(r.get("ap").asMap()) + " -" + r.get("t").asString()
          + stable(r.get("rp").asMap()) + "-> " + sorted(r.get("bl").asList()) + stable(r.get("bp").asMap()));
    }
    Collections.sort(out);
    return out;
  }

  private static Map<String, Object> stable(Map<String, Object> props) {
    Map<String, Object> m = new TreeMap<>(props);
    m.keySet().removeAll(EXCLUDED_KEYS);
    return m;
  }

  private static List<Object> sorted(List<Object> l) {
    var c = new ArrayList<>(l);
    c.sort(Comparator.comparing(Object::toString));
    return c;
  }
}
