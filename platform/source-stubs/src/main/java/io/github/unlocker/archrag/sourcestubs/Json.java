package io.github.unlocker.archrag.sourcestubs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Минимальные сериализация и разбор JSON для заглушек и fixtures: без внешних библиотек. */
final class Json {

  private Json() {}

  static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    append(sb, value);
    return sb.toString();
  }

  /** Разбор JSON в {@code Map}/{@code List}/{@code String}/{@code Long}/{@code Double}/{@code Boolean}/{@code null}. */
  static Object parse(String text) {
    Parser p = new Parser(text);
    Object v = p.value();
    p.skipWs();
    if (p.pos != text.length()) {
      throw new IllegalArgumentException("trailing characters at " + p.pos);
    }
    return v;
  }

  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    void skipWs() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
        pos++;
      }
    }

    Object value() {
      skipWs();
      if (pos >= s.length()) {
        throw new IllegalArgumentException("unexpected end of JSON");
      }
      char c = s.charAt(pos);
      switch (c) {
        case '{':
          return object();
        case '[':
          return array();
        case '"':
          return string();
        case 't':
          return literal("true", Boolean.TRUE);
        case 'f':
          return literal("false", Boolean.FALSE);
        case 'n':
          return literal("null", null);
        default:
          return number();
      }
    }

    private Object literal(String word, Object result) {
      if (!s.startsWith(word, pos)) {
        throw new IllegalArgumentException("unexpected token at " + pos);
      }
      pos += word.length();
      return result;
    }

    private Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      pos++;
      skipWs();
      if (peek() == '}') {
        pos++;
        return m;
      }
      while (true) {
        skipWs();
        String key = string();
        skipWs();
        expect(':');
        m.put(key, value());
        skipWs();
        if (peek() == ',') {
          pos++;
        } else {
          expect('}');
          return m;
        }
      }
    }

    private List<Object> array() {
      List<Object> l = new ArrayList<>();
      pos++;
      skipWs();
      if (peek() == ']') {
        pos++;
        return l;
      }
      while (true) {
        l.add(value());
        skipWs();
        if (peek() == ',') {
          pos++;
        } else {
          expect(']');
          return l;
        }
      }
    }

    private char peek() {
      if (pos >= s.length()) {
        throw new IllegalArgumentException("unexpected end of JSON at " + pos);
      }
      return s.charAt(pos);
    }

    private void expect(char c) {
      if (pos >= s.length() || s.charAt(pos) != c) {
        throw new IllegalArgumentException("expected '" + c + "' at " + pos);
      }
      pos++;
    }

    private String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (pos < s.length()) {
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          char e = s.charAt(pos++);
          switch (e) {
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
              pos += 4;
            }
            default -> sb.append(e);
          }
        } else {
          sb.append(c);
        }
      }
      throw new IllegalArgumentException("unterminated string");
    }

    private Object number() {
      int start = pos;
      while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
        pos++;
      }
      String t = s.substring(start, pos);
      if (t.isEmpty()) {
        throw new IllegalArgumentException("unexpected token at " + start);
      }
      return t.chars().allMatch(ch -> ch == '-' || Character.isDigit(ch)) ? (Object) Long.parseLong(t)
          : (Object) Double.parseDouble(t);
    }
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
