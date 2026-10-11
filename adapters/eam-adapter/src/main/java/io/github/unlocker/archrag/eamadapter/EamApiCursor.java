package io.github.unlocker.archrag.eamadapter;

import java.time.Instant;

/**
 * Курсор {@link EamApiConnector}: позиция чтения списков {@code eam-api}. Формат закрыт внутри пакета и
 * непрозрачен для poller'а.
 *
 * <ul>
 *   <li>{@code S,<тип>,<страница>,<размер>,<watermark>} — внутри полного snapshot;
 *   <li>{@code I,<тип>,<страница>,<размер>,<от>,<watermark>} — внутри инкрементального прохода: читаем объекты,
 *       изменённые не раньше {@code от};
 *   <li>{@code W,<watermark>} — между проходами: максимальный {@code datetime}, увиденный источником.
 * </ul>
 *
 * Значения времени — ISO-8601 UTC, запятых они не содержат.
 */
record EamApiCursor(Mode mode, int type, int page, int size, Instant from, Instant watermark) {

  enum Mode {
    SNAPSHOT,
    INCREMENTAL,
    WATERMARK
  }

  static EamApiCursor snapshotStart(int size) {
    return new EamApiCursor(Mode.SNAPSHOT, 0, 1, size, Instant.EPOCH, Instant.EPOCH);
  }

  static EamApiCursor watermark(Instant watermark) {
    return new EamApiCursor(Mode.WATERMARK, 0, 1, 0, watermark, watermark);
  }

  /** Начало инкрементального прохода от водяного знака. */
  EamApiCursor incrementalStart(int size) {
    return new EamApiCursor(Mode.INCREMENTAL, 0, 1, size, watermark, watermark);
  }

  String encode() {
    return switch (mode) {
      case SNAPSHOT -> "S," + type + "," + page + "," + size + "," + watermark;
      case INCREMENTAL -> "I," + type + "," + page + "," + size + "," + from + "," + watermark;
      case WATERMARK -> "W," + watermark;
    };
  }

  /** @throws IllegalArgumentException если строка не является курсором этого коннектора */
  static EamApiCursor parse(String text) {
    try {
      String[] p = text.split(",");
      return switch (p[0]) {
        case "S" -> check(new EamApiCursor(Mode.SNAPSHOT, Integer.parseInt(p[1]), Integer.parseInt(p[2]),
            Integer.parseInt(p[3]), Instant.EPOCH, Instant.parse(p[4])), 5, p.length);
        case "I" -> check(new EamApiCursor(Mode.INCREMENTAL, Integer.parseInt(p[1]), Integer.parseInt(p[2]),
            Integer.parseInt(p[3]), Instant.parse(p[4]), Instant.parse(p[5])), 6, p.length);
        case "W" -> check(watermark(Instant.parse(p[1])), 2, p.length);
        default -> throw new IllegalArgumentException("unknown cursor mode");
      };
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("malformed eam-api cursor", e);
    }
  }

  private static EamApiCursor check(EamApiCursor c, int expected, int actual) {
    if (expected != actual || (c.mode != Mode.WATERMARK && (c.type < 0 || c.page < 1 || c.size < 1))) {
      throw new IllegalArgumentException("malformed eam-api cursor");
    }
    return c;
  }
}
