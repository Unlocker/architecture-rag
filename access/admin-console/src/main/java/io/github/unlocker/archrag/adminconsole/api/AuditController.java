package io.github.unlocker.archrag.adminconsole.api;

import io.github.unlocker.archrag.adminconsole.pg.ConsoleReadRepository;
import io.github.unlocker.archrag.adminconsole.pg.Page;
import io.github.unlocker.archrag.adminconsole.pg.PageRequest;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Audit;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only API аудита админских операций ingestion ({@code admin_audit}); тело запроса не отдаётся. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

  private final ConsoleReadRepository repository;

  AuditController(ConsoleReadRepository repository) {
    this.repository = repository;
  }

  /** Журнал: фильтры {@code operation}, {@code status} и {@code started_at} в {@code [from, to)}. */
  @GetMapping
  public Page<Audit> audit(
      @RequestParam(name = "operation", required = false) String operation,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageRequest paging = PageRequest.of(page, size);
    if (from != null && to != null && !from.isBefore(to)) {
      throw new InvalidRequestException("from must be before to");
    }
    return repository.audit(
        IdentityController.token(operation, "operation"), IdentityController.token(status, "status"), from, to, paging);
  }
}
