package io.github.unlocker.archrag.adaptercore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальные чтение и запись JSON без внешних библиотек (новую зависимость в задаче не вводим).
 *
 * <p>Читает недоверенный ввод: глубина вложенности ограничена, ошибки разбора — {@link
 * IllegalArgumentException} без содержимого документа. Запись детерминирована: порядок ключей
 * сохраняется, поэтому одинаковое состояние даёт одинаковые байты (raw payload адресуется SHA-256).
 */
final class Json {

  private static final int MAX_DEPTH = 64;

  private final String s;
  private int pos;

  private Json(String s) {
    this.s = s;
  }

  /** Разбирает документ в {@code Map}/{@code List}/{@code String}/{@code Long}/{@code Double}/{@code Boolean}/{@code null}. */
  static Object parse(String text) {
    Json p = new Json(text);
    Object v = p.value(0);
    p.skipWs();
    if (p.pos != text.length()) {
      throw p.error("trailing data");
    }
    return v;
  }

  /** Разбирает документ, корнем которого обязан быть объект. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> parseObject(String text) {
    if (parse(text) instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    throw new IllegalArgumentException("JSON object expected");
  }

  private Object value(int depth) {
    if (depth > MAX_DEPTH) {
      throw error("too deep");
    }
    skipWs();
    if (pos >= s.length()) {
      throw error("unexpected end");
    }
    char c = s.charAt(pos);
    return switch (c) {
      case '{' -> object(depth);
      case '[' -> array(depth);
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object(int depth) {
    Map<String, Object> m = new LinkedHashMap<>();
    pos++;
    skipWs();
    if (peek() == '}') {
      pos++;
      return m;
    }
    while (true) {
      skipWs();
      if (peek() != '"') {
        throw error("key expected");
      }
      String k = string();
      skipWs();
      expect(':');
      m.put(k, value(depth + 1));
      skipWs();
      char c = next();
      if (c == '}') {
        return m;
      }
      if (c != ',') {
        throw error("',' expected");
      }
    }
  }

  private List<Object> array(int depth) {
    List<Object> l = new ArrayList<>();
    pos++;
    skipWs();
    if (peek() == ']') {
      pos++;
      return l;
    }
    while (true) {
      l.add(value(depth + 1));
      skipWs();
      char c = next();
      if (c == ']') {
        return l;
      }
      if (c != ',') {
        throw error("',' expected");
      }
    }
  }

  private String string() {
    pos++;
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = next();
      switch (c) {
        case '"' -> {
          return sb.toString();
        }
        case '\\' -> {
          char e = next();
          switch (e) {
            case '"', '\\', '/' -> sb.append(e);
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              if (pos + 4 > s.length()) {
                throw error("bad escape");
              }
              try {
                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
              } catch (NumberFormatException ex) {
                throw error("bad escape");
              }
              pos += 4;
            }
            default -> throw error("bad escape");
          }
        }
        default -> {
          if (c < 0x20) {
            throw error("control character");
          }
          sb.append(c);
        }
      }
    }
  }

  private Object number() {
    int start = pos;
    while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
    String t = s.substring(start, pos);
    try {
      if (t.isEmpty()) {
        throw error("unexpected character");
      }
      if (t.chars().allMatch(ch -> ch == '-' || (ch >= '0' && ch <= '9'))) {
        return Long.parseLong(t);
      }
      return Double.parseDouble(t);
    } catch (NumberFormatException e) {
      throw error("bad number");
    }
  }

  private Object literal(String word, Object value) {
    if (!s.startsWith(word, pos)) {
      throw error("bad literal");
    }
    pos += word.length();
    return value;
  }

  private void skipWs() {
    while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= s.length()) {
      throw error("unexpected end");
    }
    return s.charAt(pos);
  }

  private char next() {
    char c = peek();
    pos++;
    return c;
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("'" + c + "' expected");
    }
  }

  private IllegalArgumentException error(String what) {
    // Содержимое документа в сообщение не попадает: оно недоверенное.
    return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + what);
  }

  static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    append(sb, value);
    return sb.toString();
  }

  private static void append(StringBuilder sb, Object value) {
    switch (value) {
      case null -> sb.append("null");
      case Boolean b -> sb.append(b);
      case Number n -> sb.append(n);
      case Map<?, ?> m -> {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          quote(sb, String.valueOf(e.getKey()));
          sb.append(':');
          append(sb, e.getValue());
        }
        sb.append('}');
      }
      case Collection<?> c -> {
        sb.append('[');
        boolean first = true;
        for (Object o : c) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          append(sb, o);
        }
        sb.append(']');
      }
      default -> quote(sb, value.toString());
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
