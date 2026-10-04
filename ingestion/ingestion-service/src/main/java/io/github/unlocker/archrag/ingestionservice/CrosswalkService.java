package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.CrosswalkConflictException;
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Загрузка approved crosswalk через {@link IdentityMapping#approve}. Каждый элемент обрабатывается отдельно и пишется
 * в аудит отдельной записью: конфликт или невалидный элемент не мешает остальным. Слияние двух существующих разных
 * {@code gid} здесь не делается (E3); узлы графа под новый {@code gid} переписывает следующий replay или rebuild.
 */
@Component
public class CrosswalkService {

  static final String OPERATION = "CROSSWALK";

  private final IdentityMapping identity;
  private final AdminAudit audit;

  public CrosswalkService(IdentityMapping identity, AdminAudit audit) {
    this.identity = identity;
    this.audit = audit;
  }

  /** Применяет элементы по порядку; {@code approvedBy} — {@code sub} токена. */
  public List<CrosswalkResult> load(List<CrosswalkItem> items, String approvedBy) {
    List<CrosswalkResult> results = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      results.add(loadOne(i, items.get(i), approvedBy));
    }
    return results;
  }

  private CrosswalkResult loadOne(int index, CrosswalkItem item, String approvedBy) {
    Map<String, Object> request = describe(index, item);
    long auditId = audit.start(OPERATION, approvedBy, request, null);
    CrosswalkResult result;
    try {
      Crosswalk crosswalk = new Crosswalk(key(item.left()), key(item.right()), approvedBy, item.reason(), Instant.now());
      UUID gid = identity.approve(crosswalk);
      result = new CrosswalkResult(index, CrosswalkResult.Status.APPLIED, gid);
    } catch (CrosswalkConflictException e) {
      result = new CrosswalkResult(index, CrosswalkResult.Status.CONFLICT, null);
    } catch (IllegalArgumentException | NullPointerException e) {
      result = new CrosswalkResult(index, CrosswalkResult.Status.INVALID, null);
    } catch (RuntimeException e) {
      audit.finish(auditId, false, null, e.getClass().getSimpleName());
      throw e;
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("status", result.status().name());
    summary.put("gid", result.gid() == null ? null : result.gid().toString());
    audit.finish(auditId, result.status() == CrosswalkResult.Status.APPLIED, summary, null);
    return result;
  }

  private static SourceKey key(CrosswalkItem.KeyDto dto) {
    return new SourceKey(SourceSystemCode.valueOf(dto.source()), dto.sourceType(), dto.sourceId());
  }

  /** Ключи и причина; reason идёт в аудит как есть (подтверждение оператора), но усекается. */
  private static Map<String, Object> describe(int index, CrosswalkItem item) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("index", index);
    m.put("left", item.left() == null ? null : item.left().toString());
    m.put("right", item.right() == null ? null : item.right().toString());
    String reason = item.reason();
    m.put("reason", reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200));
    return m;
  }
}
