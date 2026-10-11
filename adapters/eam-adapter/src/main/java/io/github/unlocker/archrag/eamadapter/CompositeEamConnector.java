package io.github.unlocker.archrag.eamadapter;

import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Один источник {@code EAM} из двух половин: {@code eam-api} ({@code PLATFORM}, {@code SOLUTION}, {@code IT_SYSTEM})
 * и старая заглушка ({@code TEAM}, {@code SERVICE_DEPENDENCY}), в которой их пока нет в реальном формате (решение 2
 * UNLOCKER-255). {@code IT_SYSTEM} старой заглушки отбрасывается. Долг PoC: legacy-половину убирает задача про реальный
 * EAM.
 *
 * <p>Нужен один коннектор, а не два адаптера: {@code Reconciler} tombstone-ит <em>все</em> активные записи источника
 * {@code EAM}, которых нет в snapshot-прогоне, поэтому два прогона под одним {@code EAM} снесли бы записи друг друга.
 * Snapshot — один прогон: сначала {@code eam-api}, потом legacy; {@code snapshotComplete} ставится только после
 * последней страницы legacy-половины. Инкремент читает обе половины по очереди.
 *
 * <p>Курсор непрозрачен для poller'а и закрыт в этом классе: {@code S|I ; A|L ; eamapi:<курсор> | legacy:<курсор>}
 * (части в base64url): режим (snapshot или инкремент), читаемая сейчас половина и курсор каждой половины.
 */
public final class CompositeEamConnector implements SourceConnector, AutoCloseable {

  /** Типы, которые отдаёт legacy-половина. */
  static final Set<String> LEGACY_TYPES = Set.of("TEAM", "SERVICE_DEPENDENCY");

  private static final Set<String> API_TYPES = Set.of("PLATFORM", "SOLUTION", "IT_SYSTEM");

  private final SourceConnector api;
  private final SourceConnector legacy;

  public CompositeEamConnector(SourceConnector api, SourceConnector legacy) {
    this.api = api;
    this.legacy = legacy;
  }

  @Override
  public SourceSystem system() {
    return SourceSystem.EAM;
  }

  @Override
  public ChangePage fetchChanges(String cursor, int limit) {
    State s = cursor == null ? new State(true, false, null, null) : State.parse(cursor);
    if (!s.legacyPhase()) {
      ChangePage p = api.fetchChanges(s.api(), limit);
      if (p.nextCursor() == null) {
        throw new IllegalStateException("eam-api answered a page without a cursor");
      }
      return new ChangePage(p.changes(), new State(s.snapshot(), !p.hasMore(), p.nextCursor(), s.legacy()).encode(),
          true, false);
    }
    ChangePage p = legacy.fetchChanges(s.legacy(), limit);
    List<SourceChange> changes = p.changes().stream().filter(c -> LEGACY_TYPES.contains(c.sourceType())).toList();
    if (p.hasMore()) {
      return new ChangePage(changes, new State(s.snapshot(), true, s.api(), p.nextCursor()).encode(), true, false);
    }
    if (s.snapshot() && !p.snapshotComplete()) {
      // Маркер полноты ставится только если legacy-половина подтвердила полный проход.
      throw new IllegalStateException("legacy eam ended snapshot without snapshot-complete");
    }
    return new ChangePage(changes, new State(false, false, s.api(), p.nextCursor()).encode(), false, s.snapshot());
  }

  @Override
  public Optional<SourceChange> fetchById(String sourceType, String sourceId) {
    if (API_TYPES.contains(sourceType)) {
      return api.fetchById(sourceType, sourceId);
    }
    if (LEGACY_TYPES.contains(sourceType)) {
      return legacy.fetchById(sourceType, sourceId);
    }
    return Optional.empty();
  }

  @Override
  public void close() {
    try {
      closeQuietly(api);
    } finally {
      closeQuietly(legacy);
    }
  }

  private static void closeQuietly(SourceConnector c) {
    if (c instanceof AutoCloseable a) {
      try {
        a.close();
      } catch (Exception e) {
        // Закрытие при остановке адаптера: нечего восстанавливать, значения источника в сообщение не попадают.
        System.getLogger(CompositeEamConnector.class.getName())
            .log(System.Logger.Level.WARNING, "connector close failed: " + e.getClass().getName());
      }
    }
  }

  private record State(boolean snapshot, boolean legacyPhase, String api, String legacy) {

    String encode() {
      return (snapshot ? "S" : "I") + ";" + (legacyPhase ? "L" : "A") + ";eamapi:" + part(api) + "|legacy:" + part(legacy);
    }

    static State parse(String text) {
      try {
        String[] p = text.split(";", 3);
        String[] halves = p[2].split("\\|", 2);
        if (!halves[0].startsWith("eamapi:") || !halves[1].startsWith("legacy:")) {
          throw new IllegalArgumentException();
        }
        return new State(mode(p[0], "S", "I"), mode(p[1], "L", "A"), unpart(halves[0].substring(7)),
            unpart(halves[1].substring(7)));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("malformed eam cursor", e);
      }
    }

    private static boolean mode(String v, String yes, String no) {
      if (v.equals(yes)) {
        return true;
      }
      if (v.equals(no)) {
        return false;
      }
      throw new IllegalArgumentException();
    }

    private static String part(String cursor) {
      return cursor == null ? "-" : Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    private static String unpart(String v) {
      return v.equals("-") ? null : new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8);
    }
  }
}
