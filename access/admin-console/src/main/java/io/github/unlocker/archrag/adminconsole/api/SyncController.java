package io.github.unlocker.archrag.adminconsole.api;

import io.github.unlocker.archrag.adminconsole.graph.GraphReads;
import io.github.unlocker.archrag.adminconsole.pg.ConsoleReadRepository;
import io.github.unlocker.archrag.adminconsole.pg.Page;
import io.github.unlocker.archrag.adminconsole.pg.PageRequest;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Checkpoint;
import io.github.unlocker.archrag.adminconsole.pg.Rows.DlqEntry;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Event;
import io.github.unlocker.archrag.adminconsole.pg.Rows.EventDetail;
import io.github.unlocker.archrag.adminconsole.pg.Rows.SourceStatus;
import io.github.unlocker.archrag.adminconsole.pg.Rows.SyncRunInfo;
import io.github.unlocker.archrag.adminconsole.pg.SyncSources;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.templates.ConsoleTemplates;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API синхронизации: сводка по источникам, журнал событий, DLQ. Payload не отдаётся, только
 * {@code payloadRef} и {@code contentHash}.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

  /** Значения {@code SyncRun.adapter} в графе ({@code SourceSystemCode}), по порядку {@link SyncSources#CODES}. */
  private static final List<String> ADAPTERS = SyncSources.CODES.stream().map(String::toUpperCase).toList();

  static final String HISTORY_NOTE =
      "Status history is not stored; dlqEntries lists quarantine records of this event.";

  private final ConsoleReadRepository repository;
  private final GraphReads graph;

  SyncController(ConsoleReadRepository repository, GraphReads graph) {
    this.repository = repository;
    this.graph = graph;
  }

  /**
   * Все четыре источника (с нулями, если данных нет): checkpoints, последний {@code SyncRun}, число событий по
   * статусам, время последнего {@code PROJECTED} и отставание от него.
   */
  @GetMapping("/sources")
  public List<SourceStatus> sources() {
    Map<String, List<Checkpoint>> checkpoints = repository.checkpoints();
    Map<String, Map<String, Long>> counts = repository.eventCounts();
    Map<String, Instant> projected = repository.lastProjected();
    Map<String, SyncRunInfo> runs = latestRuns();
    Instant now = Instant.now();
    return SyncSources.CODES.stream()
        .map(
            code -> {
              Map<String, Long> byStatus = new LinkedHashMap<>();
              SyncSources.STATUSES.forEach(
                  s -> byStatus.put(s, counts.getOrDefault(code, Map.of()).getOrDefault(s, 0L)));
              Instant last = projected.get(code);
              Long lag = last == null ? null : Math.max(0, Duration.between(last, now).toSeconds());
              return new SourceStatus(
                  code, checkpoints.getOrDefault(code, List.of()), runs.get(code), byStatus, last, lag);
            })
        .toList();
  }

  /** Журнал {@code inbox_event}: фильтры по источнику, статусу и {@code received_at} в {@code [from, to)}. */
  @GetMapping("/events")
  public Page<Event> events(
      @RequestParam(name = "source", required = false) String source,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageRequest paging = PageRequest.of(page, size);
    if (from != null && to != null && !from.isBefore(to)) {
      throw new InvalidRequestException("from must be before to");
    }
    return repository.events(SyncSources.toStored(source), SyncSources.status(status), from, to, paging);
  }

  /**
   * Событие по {@code eventId}. Ключ события составной {@code (source, eventId)}, поэтому при совпадении
   * {@code eventId} у нескольких источников нужен параметр {@code source}.
   */
  @GetMapping("/events/{eventId}")
  public EventDetail event(
      @PathVariable("eventId") String eventId,
      @RequestParam(name = "source", required = false) String source) {
    List<Event> found = repository.eventsById(eventId, SyncSources.toStored(source));
    if (found.isEmpty()) {
      throw new NotFoundException("event not found");
    }
    if (found.size() > 1) {
      throw new InvalidRequestException("eventId is ambiguous; specify source");
    }
    Event event = found.getFirst();
    String stored = SyncSources.toStored(event.source());
    String reason = repository.errorReason(stored, eventId).orElse(null);
    return new EventDetail(event, reason, repository.dlqOfEvent(stored, eventId), HISTORY_NOTE);
  }

  /** Записи {@code dlq_entry} с причиной; {@code replayed=false} — только открытые. */
  @GetMapping("/dlq")
  public Page<DlqEntry> dlq(
      @RequestParam(name = "source", required = false) String source,
      @RequestParam(name = "replayed", required = false) Boolean replayed,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageRequest paging = PageRequest.of(page, size);
    return repository.dlq(SyncSources.toStored(source), replayed, paging);
  }

  private Map<String, SyncRunInfo> latestRuns() {
    var budget = graph.ceilings();
    QueryResult result =
        graph.execute(ConsoleTemplates.LATEST_SYNC_RUNS.id(), Map.of("adapters", ADAPTERS), budget);
    Map<String, SyncRunInfo> out = new LinkedHashMap<>();
    for (Map<String, Object> row : result.rows()) {
      out.put(
          ((String) row.get("adapter")).toLowerCase(),
          new SyncRunInfo(
              (String) row.get("runId"),
              (String) row.get("startedAt"),
              (String) row.get("endedAt"),
              (String) row.get("status"),
              number(row.get("fetched")),
              number(row.get("applied")),
              number(row.get("failed"))));
    }
    return out;
  }

  private static Long number(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }
}
