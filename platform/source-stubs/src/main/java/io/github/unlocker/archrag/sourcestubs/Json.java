package io.github.unlocker.archrag.sourcestubs;

import java.util.Collection;
import java.util.Map;

/** Минимальная сериализация в JSON для тела webhook: без внешних библиотек. */
final class Json {

  private Json() {}

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
