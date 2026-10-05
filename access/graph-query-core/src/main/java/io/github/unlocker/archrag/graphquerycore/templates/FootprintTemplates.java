package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.Set;

/** Шаблоны runtime footprint: где развёрнута система. */
public final class FootprintTemplates {

  /** ID шаблона {@link #FIND_RUNTIME_FOOTPRINT}. */
  public static final String FIND_RUNTIME_FOOTPRINT_ID = "find_runtime_footprint";

  /**
   * Развёртывания системы: одна строка на deployment, вычислительные узлы собраны в {@code instances}.
   *
   * <p>Параметры: {@code systemGid}, {@code environment} (может быть {@code null}: без фильтра).
   * Инварианты: только действующие факты ({@code isCurrent}, у {@code RUNS_ON} и {@code HOSTED_ON}
   * ещё и {@code validTo IS NULL}); если система существует, строка есть всегда, при отсутствии
   * подходящих deployments поля deployment равны {@code null}; если узла нет или он не {@code
   * ITSystem}, строк нет. Фильтр окружения задан одним условием {@code $environment IS NULL OR ...},
   * без цепочек {@code OR EXISTS}: они дорого планируются на холодном кэше.
   */
  public static final QueryTemplate FIND_RUNTIME_FOOTPRINT =
      new QueryTemplate(
          FIND_RUNTIME_FOOTPRINT_ID,
          """
          MATCH (sys:ITSystem {gid: $systemGid})
          OPTIONAL MATCH (sys)-[:DECOMPOSED_INTO]->(svc:Service)-[:HAS_DEPLOYMENT]->(d:Deployment)
                -[:IN_ENVIRONMENT]->(env:Environment)
          WHERE sys.isCurrent = true AND svc.isCurrent = true AND d.isCurrent = true
            AND env.isCurrent = true AND ($environment IS NULL OR env.code = $environment)
          OPTIONAL MATCH (d)-[ro:RUNS_ON]->(ci:ComputeInstance)
          WHERE ro.validTo IS NULL AND ci.isCurrent = true
          OPTIONAL MATCH (ci)-[ho:HOSTED_ON]->(ps:PhysicalServer)
          WHERE ho.validTo IS NULL AND ps.isCurrent = true
          WITH sys, svc, d, env,
               collect(CASE WHEN ci IS NULL THEN null ELSE {
                 gid: ci.gid,
                 type: CASE WHEN 'VirtualMachine' IN labels(ci) THEN 'VirtualMachine'
                            WHEN 'PhysicalServer' IN labels(ci) THEN 'PhysicalServer'
                            ELSE 'ComputeInstance' END,
                 hostname: ci.hostname,
                 state: ci.state,
                 hostedOn: CASE WHEN ps IS NULL THEN null ELSE {
                   gid: ps.gid, hostname: ps.hostname, serialNumber: ps.serialNumber } END
               } END) AS instances
          RETURN sys.gid AS systemGid, sys.name AS systemName, sys.isCurrent AS systemIsCurrent,
                 d.gid AS gid, d.name AS name, d.version AS version,
                 svc.gid AS serviceGid, svc.name AS serviceName, env.code AS environment,
                 instances
          ORDER BY environment, serviceName, name
          LIMIT $limit
          """,
          Set.of("systemGid", "environment"),
          ResultKind.NODES);

  private FootprintTemplates() {}
}
