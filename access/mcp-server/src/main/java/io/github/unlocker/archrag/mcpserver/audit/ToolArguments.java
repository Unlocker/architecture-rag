package io.github.unlocker.archrag.mcpserver.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Нормализация аргументов tool для аудит-лога.
 *
 * <p>Результат детерминирован: ключи отсортированы, строки усечены до {@value #MAX_STRING}
 * символов с меткой, коллекции — до {@value #MAX_ITEMS} элементов, значения ключей, похожих на
 * секреты, заменены на {@code ***}. Маскирование эвристическое, по именам ключей. Глубина
 * вложенности ограничена {@value #MAX_DEPTH}.
 */
public final class ToolArguments {

  static final int MAX_STRING = 256;
  static final int MAX_ITEMS = 20;
  static final int MAX_DEPTH = 5;
  static final String MASK = "***";

  private static final Pattern SENSITIVE =
      Pattern.compile("token|secret|password|authorization|credential", Pattern.CASE_INSENSITIVE);

  private ToolArguments() {}

  /** Возвращает нормализованную копию аргументов; {@code null} трактуется как пустая map. */
  public static Map<String, Object> normalize(Map<String, ?> arguments) {
    return normalizeMap(arguments, 0);
  }

  private static Map<String, Object> normalizeMap(Map<?, ?> map, int depth) {
    var result = new TreeMap<String, Object>();
    if (map == null) {
      return result;
    }
    for (Map.Entry<?, ?> e : map.entrySet()) {
      String key = String.valueOf(e.getKey());
      result.put(key, SENSITIVE.matcher(key).find() ? MASK : normalizeValue(e.getValue(), depth));
    }
    return result;
  }

  private static Object normalizeValue(Object value, int depth) {
    if (depth >= MAX_DEPTH) {
      return "<depth-limit>";
    }
    return switch (value) {
      case null -> null;
      case Number n -> n;
      case Boolean b -> b;
      case Map<?, ?> m -> normalizeMap(m, depth + 1);
      case Collection<?> c -> normalizeItems(c, depth);
      case Object[] a -> normalizeItems(List.of(a), depth);
      default -> truncate(value.toString());
    };
  }

  private static List<Object> normalizeItems(Collection<?> items, int depth) {
    var result = new ArrayList<Object>();
    int i = 0;
    for (Object item : items) {
      if (i++ == MAX_ITEMS) {
        result.add("<+" + (items.size() - MAX_ITEMS) + " more>");
        break;
      }
      result.add(normalizeValue(item, depth + 1));
    }
    return result;
  }

  private static String truncate(String s) {
    return s.length() <= MAX_STRING
        ? s
        : s.substring(0, MAX_STRING) + "…<+" + (s.length() - MAX_STRING) + " chars>";
  }
}
