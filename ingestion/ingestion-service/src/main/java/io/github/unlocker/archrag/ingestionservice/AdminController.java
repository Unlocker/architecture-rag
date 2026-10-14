package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.NotQuarantinedException;
import io.github.unlocker.archrag.eventschemas.UnknownEventException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Админский эндпоинт ingestion-сервиса: replay, rebuild, reconcile, crosswalk, возврат события из карантина.
 * Доступен только со scope
 * {@value SecurityConfiguration#ADMIN_SCOPE} и ролью {@value SecurityConfiguration#ADMIN_ROLE} ({@link SecurityConfiguration}), во внешний ingress не публикуется.
 * Операции синхронные, идут под {@link AdminLock} и пишутся в аудит ({@link AdminOperations}).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  static final int MAX_CROSSWALK_ITEMS = 1000;
  static final int MAX_EVENT_ID_LENGTH = 500;
  private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9:._-]{1,200}");

  private final AdminOperations operations;
  private final ReplayService replay;
  private final RebuildService rebuild;
  private final CrosswalkService crosswalk;
  private final AdapterControlClient adapters;
  private final EventJournal journal;

  public AdminController(
      AdminOperations operations,
      ReplayService replay,
      RebuildService rebuild,
      CrosswalkService crosswalk,
      AdapterControlClient adapters,
      EventJournal journal) {
    this.operations = operations;
    this.replay = replay;
    this.rebuild = rebuild;
    this.crosswalk = crosswalk;
    this.adapters = adapters;
    this.journal = journal;
  }

  /**
   * Тело запроса replay: диапазон {@code [receivedFrom, receivedTo)} по времени приёма в журнал.
   *
   * @param source источник события, например {@code urn:corp:eam} (обязателен)
   * @param replayId необязательный UUID для повторного запуска прерванного replay; иначе генерируется
   */
  public record ReplayRequest(String source, Instant receivedFrom, Instant receivedTo, String replayId) {}

  @PostMapping("/replay")
  public ReplayResult replay(@RequestBody ReplayRequest request, @AuthenticationPrincipal Jwt jwt) {
    if (request.source() == null || !SOURCE.matcher(request.source()).matches()) {
      throw new BadRequestException("source is required");
    }
    if (request.receivedFrom() != null && request.receivedTo() != null
        && !request.receivedFrom().isBefore(request.receivedTo())) {
      throw new BadRequestException("receivedFrom must be before receivedTo");
    }
    String replayId = replayId(request.replayId());
    Map<String, Object> audited = new LinkedHashMap<>();
    audited.put("source", request.source());
    audited.put("receivedFrom", request.receivedFrom());
    audited.put("receivedTo", request.receivedTo());
    return operations.run(
        "REPLAY",
        jwt.getSubject(),
        audited,
        replayId,
        () -> replay.replay(request.source(), request.receivedFrom(), request.receivedTo(), replayId),
        AdminController::summary);
  }

  /**
   * Тело запроса возврата из карантина.
   *
   * @param source источник события, например {@code urn:corp:eam}
   * @param eventId идентификатор события в журнале, до {@value MAX_EVENT_ID_LENGTH} символов
   */
  public record QuarantineReleaseRequest(String source, String eventId) {}

  /** Результат возврата из карантина: событие стоит в {@code RETRYING}, дальше его берёт диспетчер. */
  public record QuarantineReleaseResult(String source, String eventId, String status, int attempts) {}

  /**
   * Возвращает событие из {@code QUARANTINED} в очередь повторов (счётчик попыток обнуляется) и закрывает его запись
   * DLQ. {@code 404}, если события нет, {@code 409}, если оно не в карантине.
   */
  @PostMapping("/quarantine/release")
  public ResponseEntity<Object> releaseFromQuarantine(
      @RequestBody QuarantineReleaseRequest request, @AuthenticationPrincipal Jwt jwt) {
    if (request.source() == null || !SOURCE.matcher(request.source()).matches()) {
      throw new BadRequestException("source is required");
    }
    if (request.eventId() == null || request.eventId().isBlank() || request.eventId().length() > MAX_EVENT_ID_LENGTH) {
      throw new BadRequestException("eventId must be 1.." + MAX_EVENT_ID_LENGTH + " characters");
    }
    Map<String, Object> audited = new LinkedHashMap<>();
    audited.put("source", request.source());
    audited.put("eventId", request.eventId());
    try {
      return ResponseEntity.ok(operations.run(
          "QUARANTINE_RELEASE",
          jwt.getSubject(),
          audited,
          null,
          () -> {
            JournalEntry e = journal.releaseFromQuarantine(request.source(), request.eventId());
            return new QuarantineReleaseResult(e.source(), e.eventId(), e.status().name(), e.attempts());
          },
          r -> Map.of("status", r.status(), "attempts", r.attempts())));
    } catch (UnknownEventException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown event"));
    } catch (NotQuarantinedException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "event is not quarantined"));
    }
  }

  @PostMapping("/rebuild")
  public ReplayResult rebuild(@RequestParam(defaultValue = "false") boolean confirm, @AuthenticationPrincipal Jwt jwt) {
    if (!confirm) {
      throw new BadRequestException("confirm=true is required");
    }
    String replayId = UUID.randomUUID().toString();
    return operations.run(
        "REBUILD", jwt.getSubject(), Map.of("confirm", true), replayId, () -> rebuild.rebuild(replayId), AdminController::summary);
  }

  /**
   * Запускает полный snapshot источника в его adapter-сервисе ({@code POST /control/snapshot}); удаления после него
   * делает {@code Reconciler} по маркеру. Ответ адаптера отдаётся как есть: {@code 202} или {@code 409}
   * ({@code ALREADY_RUNNING}); неизвестный источник — {@code 404}, адаптер недоступен — {@code 502}.
   *
   * @param source {@code urn:corp:<код>} или короткий код ({@code eam}, {@code scm}, {@code cmdb}, {@code deploymap})
   */
  @PostMapping("/reconcile/{source}")
  public ResponseEntity<String> reconcile(@PathVariable String source, @AuthenticationPrincipal Jwt jwt) {
    String code = AdapterControlClient.code(source);
    if (!SOURCE.matcher(code).matches()) {
      throw new BadRequestException("source is invalid");
    }
    if (!adapters.knows(code)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
          .body("{\"error\":\"unknown source\"}");
    }
    AdapterControlClient.Response r = operations.run(
        "RECONCILE", jwt.getSubject(), Map.of("source", code), null, () -> adapters.snapshot(code, jwt.getTokenValue()),
        resp -> Map.of("adapterStatus", resp.status()));
    return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
  }

  @PostMapping("/crosswalks")
  public List<CrosswalkResult> crosswalks(@RequestBody List<CrosswalkItem> items, @AuthenticationPrincipal Jwt jwt) {
    if (items.isEmpty() || items.size() > MAX_CROSSWALK_ITEMS) {
      throw new BadRequestException("items must contain 1.." + MAX_CROSSWALK_ITEMS + " elements");
    }
    return operations.locked(() -> crosswalk.load(items, jwt.getSubject()));
  }

  @ExceptionHandler(BadRequestException.class)
  ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(AdapterUnavailableException.class)
  ResponseEntity<Map<String, String>> adapterUnavailable(AdapterUnavailableException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(AdminBusyException.class)
  ResponseEntity<Map<String, String>> busy(AdminBusyException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
  }

  private static String replayId(String requested) {
    if (requested == null) {
      return UUID.randomUUID().toString();
    }
    try {
      return UUID.fromString(requested).toString();
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("replayId must be a UUID");
    }
  }

  private static Map<String, ?> summary(ReplayResult r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("total", r.total());
    m.put("statuses", r.statuses());
    return m;
  }
}
