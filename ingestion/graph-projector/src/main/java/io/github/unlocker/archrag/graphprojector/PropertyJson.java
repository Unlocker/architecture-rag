package io.github.unlocker.archrag.graphprojector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON для свойств связи в {@code PendingRelation}: Neo4j не хранит map, а библиотеку JSON в модуль
 * не добавляем.
 *
 * <p>Поддержаны ровно значения, которые пропускает {@code Invariants.copyProperties}: строка, boolean,
 * {@code Long}/{@code Integer}, {@code Double} (конечный), {@code Instant} и список из них. {@code Instant}
 * кодируется объектом {@code {"$instant": "..."}}, чтобы вернуться тем же типом; целые читаются как {@code Long},
 * дробные как {@code Double}.
 */
final class PropertyJson {

  private static final String INSTANT = "$instant";

  private PropertyJson() {}

  static String write(Map<String, Object> properties) {
    StringBuilder out = new StringBuilder();
    writeMap(out, properties);
    return out.toString();
  }

  static Map<String, Object> read(String json) {
    Parser parser = new Parser(json);
    Object value = parser.value();
    parser.end();
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("properties json must be an object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((k, v) -> result.put((String) k, v));
    return result;
  }

  private static void writeMap(StringBuilder out, Map<String, Object> map) {
    out.append('{');
    boolean first = true;
    for (var entry : new java.util.TreeMap<>(map).entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      string(out, entry.getKey());
      out.append(':');
      writeValue(out, entry.getValue());
    }
    out.append('}');
  }

  private static void writeValue(StringBuilder out, Object value) {
    switch (value) {
      case String s -> string(out, s);
      case Boolean b -> out.append(b);
      case Long l -> out.append(l);
      case Integer i -> out.append(i);
      case Double d -> out.append(d);
      case Instant t -> {
        out.append("{\"").append(INSTANT).append("\":");
        string(out, t.toString());
        out.append('}');
      }
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          writeValue(out, list.get(i));
        }
        out.append(']');
      }
      default -> throw new IllegalArgumentException("unsupported property type " + value.getClass().getName());
    }
  }

  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    void end() {
      skipSpace();
      if (pos != text.length()) {
        throw error("trailing characters");
      }
    }

    Object value() {
      skipSpace();
      if (pos >= text.length()) {
        throw error("unexpected end");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't', 'f' -> bool();
        default -> number();
      };
    }

    private Object object() {
      pos++;
      Map<String, Object> map = new LinkedHashMap<>();
      skipSpace();
      if (peek() == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipSpace();
        String key = string();
        skipSpace();
        expect(':');
        map.put(key, value());
        skipSpace();
        if (peek() == ',') {
          pos++;
        } else {
          expect('}');
          break;
        }
      }
      if (map.size() == 1 && map.get(INSTANT) instanceof String s) {
        return Instant.parse(s);
      }
      return map;
    }

    private Object array() {
      pos++;
      List<Object> list = new ArrayList<>();
      skipSpace();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        list.add(value());
        skipSpace();
        if (peek() == ',') {
          pos++;
        } else {
          expect(']');
          return list;
        }
      }
    }

    private String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= text.length()) {
          throw error("unterminated string");
        }
        char c = text.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= text.length()) {
          throw error("unterminated escape");
        }
        char e = text.charAt(pos++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > text.length()) {
              throw error("truncated unicode escape");
            }
            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> sb.append(e);
        }
      }
    }

    private Object bool() {
      if (text.startsWith("true", pos)) {
        pos += 4;
        return Boolean.TRUE;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return Boolean.FALSE;
      }
      throw error("bad literal");
    }

    private Object number() {
      int start = pos;
      while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
        pos++;
      }
      String n = text.substring(start, pos);
      if (n.isEmpty()) {
        throw error("bad value");
      }
      return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
    }

    private char peek() {
      return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char c) {
      if (peek() != c) {
        throw error("expected " + c);
      }
      pos++;
    }

    private void skipSpace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }

    private IllegalArgumentException error(String what) {
      return new IllegalArgumentException("invalid properties json: " + what + " at " + pos);
    }
  }
}
