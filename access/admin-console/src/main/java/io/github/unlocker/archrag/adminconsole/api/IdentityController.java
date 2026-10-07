package io.github.unlocker.archrag.adminconsole.api;

import io.github.unlocker.archrag.adminconsole.pg.ConsoleReadRepository;
import io.github.unlocker.archrag.adminconsole.pg.Page;
import io.github.unlocker.archrag.adminconsole.pg.PageRequest;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Candidate;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Conflict;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only API identity: конфликты источников и кандидаты на совпадение. */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {

  /** Статус и семейство меток — короткие токены; ввод только параметризуется, но мусор отсекается. */
  static final Pattern TOKEN = Pattern.compile("[A-Za-z_]{1,64}");

  private final ConsoleReadRepository repository;

  IdentityController(ConsoleReadRepository repository) {
    this.repository = repository;
  }

  /** {@code source_conflict}: фильтры {@code status} ({@code OPEN}/{@code RESOLVED}) и {@code gid}. */
  @GetMapping("/conflicts")
  public Page<Conflict> conflicts(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "gid", required = false) String gid,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageRequest paging = PageRequest.of(page, size);
    return repository.conflicts(token(status, "status"), gid(gid), paging);
  }

  /** {@code identity_candidate}: фильтры {@code status} и {@code labelFamily}. */
  @GetMapping("/candidates")
  public Page<Candidate> candidates(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "labelFamily", required = false) String labelFamily,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageRequest paging = PageRequest.of(page, size);
    return repository.candidates(token(status, "status"), token(labelFamily, "labelFamily"), paging);
  }

  static String token(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!TOKEN.matcher(value).matches()) {
      throw new InvalidRequestException(name + " is malformed");
    }
    return value;
  }

  private static String gid(String gid) {
    if (gid == null || gid.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(gid.strip()).toString();
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("gid must be a UUID");
    }
  }
}
