package io.github.unlocker.archrag.sourcestubs;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Начальные fixtures четырёх источников: репрезентативный вертикальный срез {@code ITSystem ->
 * Service -> Deployment -> Environment -> ComputeInstance} плюс {@code Repository} и {@code Team}.
 * Идентификаторы согласованы между источниками: сервис SCM ссылается на систему EAM, deployment —
 * на сервис, environment и хосты CMDB.
 */
public final class StubSources {

  public static final String IT_SYSTEM = "IT_SYSTEM";
  public static final String TEAM = "TEAM";
  public static final String SERVICE = "SERVICE";
  public static final String REPOSITORY = "REPOSITORY";
  public static final String COMPUTE_INSTANCE = "COMPUTE_INSTANCE";
  /** Зависимость между сервисами в EAM: {@code from}/{@code to} — коды сервисов (id в SCM). */
  public static final String SERVICE_DEPENDENCY = "SERVICE_DEPENDENCY";

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
    s.upsert(IT_SYSTEM, "EAM-1042", fields("name", "Payments Core", "ownerTeam", "TEAM-PAY"));
    s.upsert(
        SERVICE_DEPENDENCY,
        "dep-payments-api-ledger-api",
        fields(
            "from", "svc-payments-api",
            "to", "svc-ledger-api",
            "kind", "SYNC",
            "protocol", "HTTP",
            "criticality", "HIGH"));
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
        fields("name", "payments-api", "systemCode", "EAM-1042", "repositoryId",
            "repo-payments-api"));
    s.upsert(
        SERVICE,
        "svc-ledger-api",
        fields("name", "ledger-api", "systemCode", "EAM-2001"));
    return s;
  }

  public static StubSource cmdb(Clock clock) {
    StubSource s = new StubSource(SourceSystem.CMDB, clock);
    s.upsert(
        COMPUTE_INSTANCE,
        "vm-pay-01",
        fields("hostname", "vm-pay-01.prod.example.org", "kind", "VIRTUAL_MACHINE", "state", "RUNNING",
            "environment", "prod"));
    return s;
  }

  /**
   * Deploy map в формате {@link DeployMapFormat#FORMAT_V1}. Позитивный сценарий согласован с SCM и
   * EAM: аннотация {@code eam-system-id} {@code 5101} — это {@link EamApiSeed#SYSTEM_PAYMENTS_CORE}
   * ({@code eam_id} {@code EAM-1042}), то есть {@code systemCode} сервиса SCM {@code
   * svc-payments-api}; {@code git-repo-url} в ssh-форме нормализуется в {@code url} репозитория
   * SCM {@code repo-payments-api}. Негативные сценарии — отдельными хелперами {@code addDeployment*}.
   */
  public static StubSource deployMap(Clock clock) {
    StubSource s = new StubSource(SourceSystem.DEPLOY_MAP, clock);
    s.upsert(DeployMapFormat.ENVIRONMENT, "prod", fields("format", DeployMapFormat.FORMAT, "name",
        "Production"));
    s.upsert(
        DeployMapFormat.DEPLOYMENT,
        "dep-payments-api-prod",
        deploymentV1(
            "payments-api",
            "svc-payments-api",
            "1.4.2",
            String.valueOf(EamApiSeed.SYSTEM_PAYMENTS_CORE),
            PAYMENTS_API_SSH_URL,
            PAYMENTS_API_COMMIT));
    return s;
  }

  /** Commit seed-деплоя {@code dep-payments-api-prod}: 40 hex в нижнем регистре. */
  public static final String PAYMENTS_API_COMMIT = "9fceb02d0ae598e95dc970b74767f19372d61af8";

  /** URL репозитория seed-деплоя в ssh-форме; нормализуется в {@code url} репозитория SCM. */
  public static final String PAYMENTS_API_SSH_URL = "git@git.example.org:pay/payments-api.git";

  /** Сменённый commit для {@link #addDeploymentRedeploy}. */
  public static final String PAYMENTS_API_NEW_COMMIT = "1b4f0e9851971998e732078544c96b36c3d01ced";

  /**
   * Добавляет деплой репозитория, которого нет в SCM.
   *
   * @return идентификатор добавленного деплоя
   */
  public static String addDeploymentWithUnknownRepository(StubSource deployMap) {
    String id = "dep-unknown-repo-prod";
    deployMap.upsert(
        DeployMapFormat.DEPLOYMENT,
        id,
        deploymentV1("unknown-repo", "svc-payments-api", "0.1.0",
            String.valueOf(EamApiSeed.SYSTEM_PAYMENTS_CORE),
            "https://git.example.org/pay/unknown-repo.git", PAYMENTS_API_COMMIT));
    return id;
  }

  /**
   * Добавляет деплой репозитория {@code payments-api} с системой {@code 5102} (Ledger), хотя
   * сервис репозитория принадлежит {@code 5101}.
   *
   * @return идентификатор добавленного деплоя
   */
  public static String addDeploymentWithSystemMismatch(StubSource deployMap) {
    String id = "dep-payments-api-mismatch";
    deployMap.upsert(
        DeployMapFormat.DEPLOYMENT,
        id,
        deploymentV1("payments-api", "svc-payments-api", "1.4.2",
            String.valueOf(EamApiSeed.SYSTEM_LEDGER), PAYMENTS_API_SSH_URL, PAYMENTS_API_COMMIT));
    return id;
  }

  /**
   * Добавляет деплой с commit, не являющимся 40 hex.
   *
   * @return идентификатор добавленного деплоя
   */
  public static String addDeploymentWithInvalidCommit(StubSource deployMap) {
    String id = "dep-payments-api-bad-commit";
    deployMap.upsert(
        DeployMapFormat.DEPLOYMENT,
        id,
        deploymentV1("payments-api", "svc-payments-api", "1.4.2",
            String.valueOf(EamApiSeed.SYSTEM_PAYMENTS_CORE), PAYMENTS_API_SSH_URL, "not-a-commit"));
    return id;
  }

  /**
   * Передеплой seed-записи {@code dep-payments-api-prod}: тот же {@code sourceId}, новый commit
   * ({@link #PAYMENTS_API_NEW_COMMIT}); {@code sourceVersion} записи растёт.
   *
   * @return идентификатор переданной записи
   */
  public static String addDeploymentRedeploy(StubSource deployMap) {
    String id = "dep-payments-api-prod";
    deployMap.upsert(
        DeployMapFormat.DEPLOYMENT,
        id,
        deploymentV1("payments-api", "svc-payments-api", "1.4.3",
            String.valueOf(EamApiSeed.SYSTEM_PAYMENTS_CORE), PAYMENTS_API_SSH_URL,
            PAYMENTS_API_NEW_COMMIT));
    return id;
  }

  /** Payload {@code DEPLOYMENT} формата {@link DeployMapFormat#FORMAT_V1} в окружении {@code prod}. */
  private static Map<String, Object> deploymentV1(
      String chart, String service, String chartVersion, String systemId, String repoUrl,
      String commit) {
    Map<String, Object> annotations =
        fields(
            DeployMapFormat.ANNOTATION_EAM_SYSTEM_ID, systemId,
            DeployMapFormat.ANNOTATION_GIT_REPO_URL, repoUrl,
            DeployMapFormat.ANNOTATION_GIT_COMMIT, commit);
    return fields(
        "format", DeployMapFormat.FORMAT_V1,
        "name", chart,
        "service", service,
        "environment", "prod",
        "chart", chart,
        "chartVersion", chartVersion,
        DeployMapFormat.CHART_META,
        fields("name", chart, "version", chartVersion, "appVersion", chartVersion, "annotations",
            annotations),
        "hosts", List.of("vm-pay-01"));
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
