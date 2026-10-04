package io.github.unlocker.archrag.sourcespi.stub;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Начальные fixtures четырёх источников: репрезентативный вертикальный срез {@code ITSystem ->
 * Service -> Deployment -> Environment -> ComputeInstance} плюс {@code Repository} и {@code Team}.
 * Идентификаторы согласованы между источниками: сервис SCM ссылается на систему EAM, deployment — на
 * сервис, environment и хосты CMDB.
 */
public final class StubSources {

  public static final String IT_SYSTEM = "IT_SYSTEM";
  public static final String TEAM = "TEAM";
  public static final String SERVICE = "SERVICE";
  public static final String REPOSITORY = "REPOSITORY";
  public static final String COMPUTE_INSTANCE = "COMPUTE_INSTANCE";

  private StubSources() {}

  /** Все четыре заглушки с начальными данными. */
  public static Map<SourceSystem, StubSource> seeded(Clock clock) {
    Map<SourceSystem, StubSource> all = new EnumMap<>(SourceSystem.class);
    all.put(SourceSystem.EAM, eam(clock));
    all.put(SourceSystem.SCM, scm(clock));
    all.put(SourceSystem.CMDB, cmdb(clock));
    all.put(SourceSystem.DEPLOY_MAP, deployMap(clock));
    return all;
  }

  public static StubSource eam(Clock clock) {
    StubSource s = new StubSource(SourceSystem.EAM, clock);
    s.upsert(TEAM, "TEAM-PAY", fields("name", "Payments Team"));
    s.upsert(IT_SYSTEM, "EAM-2001", fields("name", "Ledger", "ownerTeam", "TEAM-PAY"));
    s.upsert(
        IT_SYSTEM,
        "EAM-1042",
        fields(
            "name", "Payments Core",
            "ownerTeam", "TEAM-PAY",
            "dependsOn", List.of("EAM-2001")));
    return s;
  }

  public static StubSource scm(Clock clock) {
    StubSource s = new StubSource(SourceSystem.SCM, clock);
    s.upsert(
        REPOSITORY,
        "repo-payments-api",
        fields("url", "https://git.example.org/pay/payments-api", "defaultBranch", "main"));
    s.upsert(
        SERVICE,
        "svc-payments-api",
        fields("name", "payments-api", "systemCode", "EAM-1042", "repositoryId", "repo-payments-api"));
    return s;
  }

  public static StubSource cmdb(Clock clock) {
    StubSource s = new StubSource(SourceSystem.CMDB, clock);
    s.upsert(
        COMPUTE_INSTANCE,
        "vm-pay-01",
        fields("hostname", "vm-pay-01.prod.example.org", "state", "RUNNING", "environment", "prod"));
    return s;
  }

  public static StubSource deployMap(Clock clock) {
    StubSource s = new StubSource(SourceSystem.DEPLOY_MAP, clock);
    s.upsert(DeployMapFormat.ENVIRONMENT, "prod", fields("format", DeployMapFormat.FORMAT, "name", "Production"));
    s.upsert(
        DeployMapFormat.DEPLOYMENT,
        "dep-payments-api-prod",
        fields(
            "format", DeployMapFormat.FORMAT,
            "service", "svc-payments-api",
            "environment", "prod",
            "chart", "payments-api",
            "chartVersion", "1.4.2",
            "hosts", List.of("vm-pay-01")));
    return s;
  }

  /**
   * Добавляет в SCM сервис, который ссылается на несуществующую систему EAM (битая ссылка).
   *
   * @return идентификатор добавленного сервиса
   */
  public static String addServiceWithBrokenReference(StubSource scm) {
    String id = "svc-orphan";
    scm.upsert(SERVICE, id, fields("name", "orphan", "systemCode", "EAM-UNKNOWN"));
    return id;
  }

  /** Строит payload из пар {@code ключ, значение}; допускает {@code null}-значения. */
  public static Map<String, Object> fields(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
