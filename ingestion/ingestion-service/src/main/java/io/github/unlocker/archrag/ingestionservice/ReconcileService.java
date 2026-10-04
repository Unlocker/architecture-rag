package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.Reconciler;
import org.springframework.stereotype.Component;

/**
 * Повторно применяет missing set последнего завершённого snapshot источника. Нового snapshot не запускает
 * (ручной запуск snapshot в adapter-сервисе — UNLOCKER-211) и своего прохода по графу не делает: удаления идут
 * только через {@link Reconciler}.
 *
 * <p>Допущение: в {@link Reconciler#reconcile} передаётся {@code objectCount = 0}. У маркера нет raw payload, и
 * заявленное число объектов из строки журнала не восстановить. Проверку полноты заменяет условие: маркер должен быть в
 * статусе {@code PROJECTED}, то есть проверка по {@code objectCount} при его обработке уже прошла, а журнал
 * append-only, поэтому прогон остаётся полным.
 */
@Component
public class ReconcileService {

  static final String TYPE_SNAPSHOT_COMPLETE = "architecture.sync.snapshot-complete.v1";

  private final JournalReader reader;
  private final Reconciler reconciler;

  public ReconcileService(JournalReader reader, Reconciler reconciler) {
    this.reader = reader;
    this.reconciler = reconciler;
  }

  /**
   * @param source значение {@code source} событий, например {@code urn:corp:eam}
   * @throws SnapshotNotFoundException если маркера нет
   * @throws SnapshotNotCompleteException если последний маркер не {@code PROJECTED}
   */
  public ReconcileResult reconcile(String source) {
    StoredEvent marker = reader.latest(source, TYPE_SNAPSHOT_COMPLETE).orElseThrow(SnapshotNotFoundException::new);
    JournalEntry entry = marker.entry();
    if (entry.status() != ProcessingStatus.PROJECTED) {
      throw new SnapshotNotCompleteException();
    }
    // syncRunId маркера лежит в его sourceId (так же его передаёт EventProcessor).
    Reconciler.Report report = reconciler.reconcile(source, entry.sourceId(), entry.eventId(), 0);
    return new ReconcileResult(source, entry.sourceId(), entry.eventId(), report);
  }
}
