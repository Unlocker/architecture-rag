package io.github.unlocker.archrag.ingestionservice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Админский эндпоинт ingestion-сервиса: replay, rebuild, crosswalk. Доступен только со scope
 * {@value SecurityConfiguration#ADMIN_SCOPE} ({@link SecurityConfiguration}), во внешний ingress не публикуется.
 * Операции синхронные, идут под {@link AdminLock} и пишутся в аудит ({@link AdminOperations}).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  static final int MAX_CROSSWALK_ITEMS = 1000;
  private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9:._-]{1,200}");

  private final AdminOperations operations;
  private final ReplayService replay;
  private final RebuildService rebuild;
  private final CrosswalkService crosswalk;

  public AdminController(
      AdminOperations operations, ReplayService replay, RebuildService rebuild, CrosswalkService crosswalk) {
    this.operations = operations;
    this.replay = replay;
    this.rebuild = rebuild;
    this.crosswalk = crosswalk;
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

  @PostMapping("/rebuild")
  public ReplayResult rebuild(@RequestParam(defaultValue = "false") boolean confirm, @AuthenticationPrincipal Jwt jwt) {
    if (!confirm) {
      throw new BadRequestException("confirm=true is required");
    }
    String replayId = UUID.randomUUID().toString();
    return operations.run(
        "REBUILD", jwt.getSubject(), Map.of("confirm", true), replayId, () -> rebuild.rebuild(replayId), AdminController::summary);
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
