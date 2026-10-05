package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.Set;

/** Шаблоны runtime footprint: где развёрнута система. */
public final class RuntimeTemplates {

  /** ID шаблона {@link #RUNTIME_FOOTPRINT}. */
  public static final String RUNTIME_FOOTPRINT_ID = "runtime_footprint";

  /**
   * Развёртывания системы: одна строка на (service, deployment, compute).
   *
   * <p>Параметры: {@code systemGid}, {@code environment} ({@code null} — без фильтра). Инварианты:
   * только действующие факты ({@code isCurrent}; у темпоральной {@code RUNS_ON} ещё и {@code validTo IS NULL}, {@code HOSTED_ON} не темпоральна);
   * нет действующей системы с таким gid или она не {@code ITSystem} — строк нет; сервис без
   * подходящих deployments возвращается строкой с {@code null} в полях deployment; цели
   * {@code RUNS_ON} ограничены меткой {@code ComputeInstance} (Namespace не попадает). Узлы отдаются
   * проекциями с {@code lastSeenAt} и активными {@code sources}, рёбра — с {@code assertedBy*}.
   * Фильтр окружения — одно условие {@code $environment IS NULL OR ...}, без {@code OR EXISTS}.
   */
  public static final QueryTemplate RUNTIME_FOOTPRINT =
      new QueryTemplate(
          RUNTIME_FOOTPRINT_ID,
          """
          MATCH (s:ITSystem {gid: $systemGid}) WHERE s.isCurrent = true
          OPTIONAL MATCH (s)-[:DECOMPOSED_INTO]->(svc:Service) WHERE svc.isCurrent = true
          OPTIONAL MATCH (svc)-[hd:HAS_DEPLOYMENT]->(d:Deployment)-[:IN_ENVIRONMENT]->(e:Environment)
          WHERE d.isCurrent = true AND e.isCurrent = true
            AND ($environment IS NULL OR e.code = $environment)
          OPTIONAL MATCH (d)-[ro:RUNS_ON]->(c:ComputeInstance)
          WHERE ro.validTo IS NULL AND c.isCurrent = true
          OPTIONAL MATCH (c:VirtualMachine)-[ho:HOSTED_ON]->(p:PhysicalServer)
          WHERE p.isCurrent = true
          RETURN
            {gid: s.gid, name: s.name, lastSeenAt: toString(s.lastSeenAt),
             sources: COLLECT { MATCH (r:SourceRecord {active: true})-[a:ASSERTS]->(s)
                                RETURN {source: r.source, sourceType: r.sourceType,
                                        sourceId: r.sourceId, authority: a.authority} }} AS system,
            CASE WHEN svc IS NULL THEN null ELSE
              {gid: svc.gid, name: svc.name, lastSeenAt: toString(svc.lastSeenAt),
               sources: COLLECT { MATCH (r:SourceRecord {active: true})-[a:ASSERTS]->(svc)
                                  RETURN {source: r.source, sourceType: r.sourceType,
                                          sourceId: r.sourceId, authority: a.authority} }} END AS service,
            CASE WHEN d IS NULL THEN null ELSE
              {gid: d.gid, name: d.name, version: d.version, status: d.status,
               environment: e.code, lastSeenAt: toString(d.lastSeenAt),
               sources: COLLECT { MATCH (r:SourceRecord {active: true})-[a:ASSERTS]->(d)
                                  RETURN {source: r.source, sourceType: r.sourceType,
                                          sourceId: r.sourceId, authority: a.authority} },
               hasDeployment: {source: hd.assertedBySource, sourceType: hd.assertedByType,
                               sourceId: hd.assertedById, validFrom: null}} END AS deployment,
            CASE WHEN c IS NULL THEN null ELSE
              {gid: c.gid,
               type: CASE WHEN c:VirtualMachine THEN 'VirtualMachine'
                          WHEN c:PhysicalServer THEN 'PhysicalServer'
                          ELSE 'ComputeInstance' END,
               hostname: c.hostname, state: c.state, lastSeenAt: toString(c.lastSeenAt),
               sources: COLLECT { MATCH (r:SourceRecord {active: true})-[a:ASSERTS]->(c)
                                  RETURN {source: r.source, sourceType: r.sourceType,
                                          sourceId: r.sourceId, authority: a.authority} },
               runsOn: {source: ro.assertedBySource, sourceType: ro.assertedByType,
                        sourceId: ro.assertedById, validFrom: toString(ro.validFrom)}} END AS compute,
            CASE WHEN p IS NULL THEN null ELSE
              {gid: p.gid, hostname: p.hostname, state: p.state, lastSeenAt: toString(p.lastSeenAt),
               sources: COLLECT { MATCH (r:SourceRecord {active: true})-[a:ASSERTS]->(p)
                                  RETURN {source: r.source, sourceType: r.sourceType,
                                          sourceId: r.sourceId, authority: a.authority} },
               hostedOn: {source: ho.assertedBySource, sourceType: ho.assertedByType,
                          sourceId: ho.assertedById, validFrom: null}} END AS host
          ORDER BY svc.gid, d.gid, c.gid
          LIMIT $limit
          """,
          Set.of("systemGid", "environment"),
          ResultKind.NODES);

  private RuntimeTemplates() {}
}
