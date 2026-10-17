package io.github.unlocker.archrag.sourcestubs;

import static io.github.unlocker.archrag.sourcestubs.StubSources.fields;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Демо-профиль заглушек ({@code ARCHRAG_STUB_PROFILE=demo}): вымышленный «Nordhaven Bank» по {@code
 * docs/demo/dataset.md}. Ключи и значения берутся из этого документа, он источник истины; контрактный тест сверяет
 * профиль с его таблицами.
 *
 * <p>Профиль по умолчанию ({@link StubSources}, {@link EamApiSeed}) не меняется. Набор детерминирован: от часов зависят
 * только времена записей, «устаревшие» записи получают смещение назад ({@link #STALE_SERVICE_AGE}, {@link
 * #STALE_HOST_AGE}). Записи нынешнего seed входят в набор с теми же ключами.
 */
public final class DemoSources {

  /** Возраст {@code svc-sms-dispatcher} (кейс К-7): старше порога {@code stale-after} = 7 дней. */
  public static final Duration STALE_SERVICE_AGE = Duration.ofDays(30);

  /** Возраст {@code dep-sms-dispatcher-dev} и {@code vm-dev-legacy-01} (кейс К-7). */
  public static final Duration STALE_HOST_AGE = Duration.ofDays(21);

  /** Развёртывание для карантина (кейс К-11): commit не 40 hex, событие уходит в {@code QUARANTINED}. */
  public static final String QUARANTINE_DEPLOYMENT = "dep-card-tokenizer-test-badcommit";

  /** Выводимый из эксплуатации сервис и связанные с ним записи (кейс К-6, исчезают во время сценария). */
  public static final String RETIRED_SERVICE = "svc-legacy-statements";

  public static final String RETIRED_REPOSITORY = "repo-legacy-statements";
  public static final String RETIRED_DEPENDENCY = "dep-legacy-statements-ledger-reporting";
  public static final String RETIRED_DEPLOYMENT = "dep-legacy-statements-prod";

  /** Дубль репозитория (кейс К-5б) и дубль ВМ (кейс К-5а). */
  public static final String DUPLICATE_REPOSITORY = "repo-payments-api-legacy";

  public static final String DUPLICATE_HOST = "cmdb-ci-88014";

  private static final String GIT_HOST = "git.example.org";
  private static final String NO_SYSTEM_CODE = "EAM-9999";
  /** {@code eam-system-id} развёртывания сервиса без системы (кейс К-8): такой системы в EAM нет. */
  private static final long NO_SYSTEM_ID = 9999;
  /** Явные commit'ы нынешнего seed; правило {@link #commit} к ним не применяется. */
  private static final Map<String, String> EXPLICIT_COMMITS =
      Map.of(
          "dep-payments-api-prod", StubSources.PAYMENTS_API_COMMIT,
          "dep-payments-api-test", StubSources.PAYMENTS_API_NEW_COMMIT);

  private record Platform(long id, String name, String lifecycle) {}

  private record Solution(long id, String name, long platformId, String domain) {}

  private record ItSystem(
      long id, String eamId, String name, long productId, long platformId, String criticality,
      String team, String domain, String summary) {}

  private record Team(String id, String name, String type) {}

  private record Service(String name, String systemCode, String repo, String group, String language) {
    String key() {
      return "svc-" + name;
    }
  }

  private record Deployment(String service, String env, String version, List<String> hosts) {
    String key() {
      return "dep-" + service + "-" + env;
    }
  }

  private record Host(String id, String env, String kind, String state) {}

  private static final List<Platform> PLATFORMS =
      List.of(
          new Platform(EamApiSeed.PLATFORM_KUBERNETES, "Kubernetes Platform", "PRODUCTION"),
          new Platform(EamApiSeed.PLATFORM_POSTGRES, "PostgreSQL Platform", "PRODUCTION"),
          new Platform(3103, "Legacy VM Platform", "SUNSET"));

  private static final List<Solution> SOLUTIONS =
      List.of(
          new Solution(7201, "Card Processing", 3101, "Payments"),
          new Solution(7202, "Ledger Engine", 3102, "Payments"),
          new Solution(7203, "Settlement Engine", 3101, "Payments"),
          new Solution(7204, "Risk Decisioning", 3101, "Risk & Compliance"),
          new Solution(7205, "Digital Channels Suite", 3101, "Customer Channels"),
          new Solution(7206, "Messaging Hub", 3103, "Customer Channels"),
          new Solution(7207, "Compliance Suite", 3103, "Risk & Compliance"),
          new Solution(7208, "Customer Data Store", 3102, "Customer Channels"));

  private static final List<ItSystem> SYSTEMS =
      List.of(
          new ItSystem(5101, "EAM-1042", "Payments Core", 7201, 3101, "HIGH", "TEAM-PAY", "Payments",
              "Payments processing system"),
          new ItSystem(5102, "EAM-2001", "Ledger", 7202, 3102, "HIGH", "TEAM-LEDGER", "Payments",
              "General ledger system"),
          new ItSystem(5103, "EAM-1043", "Card Gateway", 7201, 3101, "HIGH", "TEAM-PAY", "Payments",
              "Card network gateway and authorization"),
          new ItSystem(5104, "EAM-2002", "Settlement Hub", 7203, 3101, "MEDIUM", "TEAM-LEDGER", "Payments",
              "Settlement and reconciliation with partners"),
          // Критичность вне словаря EAM (HIGH/MEDIUM/LOW): кейс К-10, значение уходит в criticalityRaw.
          new ItSystem(5201, "EAM-3001", "Fraud Screening", 7204, 3101, "CRITICAL", "TEAM-RISK",
              "Risk & Compliance", "Real-time transaction fraud scoring"),
          new ItSystem(5202, "EAM-3002", "AML Monitoring", 7207, 3103, "MEDIUM", "TEAM-RISK",
              "Risk & Compliance", "Anti-money-laundering screening and case management"),
          new ItSystem(5301, "EAM-4001", "Mobile Banking", 7205, 3101, "HIGH", "TEAM-DIGI",
              "Customer Channels", "Mobile banking backend"),
          new ItSystem(5302, "EAM-4002", "Internet Banking", 7205, 3101, "MEDIUM", "TEAM-DIGI",
              "Customer Channels", "Internet banking web and API"),
          // platform_id системы (3101) расходится с платформой решения 7206 (3103): кейс К-4б.
          new ItSystem(5303, "EAM-4003", "Notification Center", 7206, 3101, "LOW", "TEAM-PLAT",
              "Customer Channels", "Customer notifications over SMS and push"),
          new ItSystem(5304, "EAM-4004", "Customer Profile", 7208, 3102, "HIGH", "TEAM-PLAT",
              "Customer Channels", "Customer master data and consents"));

  private static final List<Team> TEAMS =
      List.of(
          new Team("TEAM-PAY", "Payments Team", "PRODUCT"),
          new Team("TEAM-LEDGER", "Ledger & Settlement Team", "PRODUCT"),
          new Team("TEAM-RISK", "Risk Team", "PRODUCT"),
          new Team("TEAM-DIGI", "Digital Channels Team", "PRODUCT"),
          new Team("TEAM-PLAT", "Platform Engineering", "PLATFORM"));

  private static final List<Service> SERVICES =
      List.of(
          new Service("payments-api", "EAM-1042", "payments-api", "pay", "java"),
          new Service("payments-router", "EAM-1042", "payments-router", "pay", "java"),
          new Service("payments-orchestrator", "EAM-1042", "payments-orchestrator", "pay", "java"),
          new Service("payments-batch", "EAM-1042", "payments-batch", "pay", "java"),
          new Service("ledger-api", "EAM-2001", "ledger-api", "ledger", "java"),
          new Service("ledger-posting", "EAM-2001", "ledger", "ledger", "java"),
          new Service("ledger-reporting", "EAM-2001", "ledger", "ledger", "java"),
          new Service("legacy-statements", "EAM-2001", "legacy-statements", "ledger", "cobol"),
          new Service("card-gateway", "EAM-1043", "card-gateway", "card", "java"),
          new Service("card-authorizer", "EAM-1043", "card-authorizer", "card", "java"),
          new Service("card-tokenizer", "EAM-1043", "card-tokenizer", "card", "go"),
          new Service("settlement-engine", "EAM-2002", "settlement-engine", "settle", "java"),
          new Service("settlement-recon", "EAM-2002", "settlement-recon", "settle", "python"),
          new Service("fx-rates", "EAM-2002", "fx-rates", "settle", "go"),
          new Service("fraud-scoring", "EAM-3001", "fraud-scoring", "risk", "python"),
          new Service("fraud-rules", "EAM-3001", "fraud-rules", "risk", "java"),
          new Service("aml-screening", "EAM-3002", "aml-screening", "risk", "java"),
          // Репозитория нет: IMPLEMENTED_IN не строится.
          new Service("aml-case-mgmt", "EAM-3002", null, "risk", "java"),
          new Service("mobile-bff", "EAM-4001", "mobile-bff", "chan", "java"),
          new Service("mobile-auth", "EAM-4001", "mobile-auth", "chan", "java"),
          new Service("ib-web", "EAM-4002", "ib-web", "chan", "typescript"),
          new Service("ib-api", "EAM-4002", "ib-api", "chan", "java"),
          new Service("notification-gateway", "EAM-4003", "notification-gateway", "plat", "java"),
          new Service("sms-dispatcher", "EAM-4003", "sms-dispatcher", "plat", "java"),
          new Service("customer-profile-api", "EAM-4004", "customer-profile-api", "plat", "java"),
          new Service("customer-consent", "EAM-4004", "customer-consent", "plat", "java"),
          // Системы EAM-9999 в EAM нет: кейс К-8.
          new Service("partner-gateway", NO_SYSTEM_CODE, "partner-gateway", "chan", "java"));

  /** Зависимости: {@code откуда -> куда[, критичность]}; {@code ASYNC/AMQP} и {@code ASYNC/HTTP} помечены в {@link #dependency}. */
  private static final String[][] DEPENDENCIES = {
    {"mobile-bff", "mobile-auth"},
    {"mobile-bff", "payments-api", "HIGH"},
    {"mobile-bff", "customer-profile-api"},
    {"mobile-bff", "notification-gateway", "ASYNC"},
    {"ib-web", "ib-api"},
    {"ib-api", "payments-api", "HIGH"},
    {"ib-api", "customer-profile-api"},
    {"payments-api", "payments-router", "HIGH"},
    {"payments-api", "ledger-api", "HIGH"},
    {"payments-api", "aml-screening"},
    {"payments-orchestrator", "payments-router"},
    {"payments-batch", "ledger-posting"},
    {"payments-router", "card-gateway", "HIGH"},
    {"payments-router", "fx-rates"},
    {"card-gateway", "card-authorizer", "HIGH"},
    {"card-authorizer", "fraud-scoring", "HIGH"},
    {"card-authorizer", "card-tokenizer"},
    {"fraud-scoring", "fraud-rules", "HIGH"},
    {"fraud-scoring", "customer-profile-api"},
    {"ledger-api", "ledger-posting", "HIGH"},
    {"ledger-reporting", "ledger-posting"},
    {"settlement-engine", "ledger-posting"},
    {"settlement-engine", "card-gateway"},
    {"settlement-recon", "settlement-engine"},
    {"aml-case-mgmt", "aml-screening"},
    {"aml-screening", "customer-profile-api"},
    {"customer-consent", "customer-profile-api"},
    {"notification-gateway", "sms-dispatcher", "ASYNC"},
    {"legacy-statements", "ledger-reporting"}
  };

  private static final List<Deployment> DEPLOYMENTS = deployments();

  private static final List<Host> HOSTS = hosts();

  private DemoSources() {}

  /** Все четыре заглушки с демо-данными; EAM API ({@link #eamApi}) отдельно. */
  public static Map<SourceSystem, StubSource> seeded(Clock clock) {
    Map<SourceSystem, StubSource> all = new EnumMap<>(SourceSystem.class);
    all.put(SourceSystem.EAM, eam(clock));
    all.put(SourceSystem.SCM, scm(clock));
    all.put(SourceSystem.CMDB, cmdb(clock));
    all.put(SourceSystem.DEPLOY_MAP, deployMap(clock));
    return all;
  }

  /** Плоская заглушка EAM: только {@code TEAM} и {@code SERVICE_DEPENDENCY}. */
  public static StubSource eam(Clock clock) {
    StubSource s = new StubSource(SourceSystem.EAM, clock);
    for (Team t : TEAMS) {
      s.upsert(StubSources.TEAM, t.id(), fields("name", t.name(), "type", t.type()));
    }
    for (String[] d : DEPENDENCIES) {
      s.upsert(StubSources.SERVICE_DEPENDENCY, dependencyKey(d), dependency(d));
    }
    return s;
  }

  /** Заглушка EAM API: платформы, решения и системы в формате реального API. */
  public static EamApiStore eamApi(Clock clock) {
    EamApiStore s = new EamApiStore(clock);
    for (Platform p : PLATFORMS) {
      s.upsert(EamApiSeed.PLATFORM_TYPE, p.id(), platformAttrs(p));
    }
    for (Solution o : SOLUTIONS) {
      s.upsert(EamApiSeed.SOLUTION_TYPE, o.id(), solutionAttrs(o));
    }
    for (ItSystem y : SYSTEMS) {
      s.upsert(EamApiSeed.IT_SYSTEM_TYPE, y.id(), systemAttrs(y));
    }
    return s;
  }

  public static StubSource scm(Clock clock) {
    StubSource s = new StubSource(SourceSystem.SCM, clock);
    Instant now = clock.instant();
    List<String> seen = new ArrayList<>();
    for (Service svc : SERVICES) {
      if (svc.repo() != null && !seen.contains(svc.repo())) {
        seen.add(svc.repo());
        s.upsert(
            StubSources.REPOSITORY,
            "repo-" + svc.repo(),
            fields("url", repoUrl(svc.group(), svc.repo()), "defaultBranch", "main"));
      }
    }
    // Тот же адрес, что у repo-payments-api, в другом регистре: кандидат identity (К-5б).
    s.upsert(
        StubSources.REPOSITORY,
        DUPLICATE_REPOSITORY,
        fields("url", "https://git.example.org/Pay/Payments-API", "defaultBranch", "master"));
    for (Service svc : SERVICES) {
      Map<String, Object> payload =
          fields("name", svc.name(), "systemCode", svc.systemCode(), "language", svc.language());
      if (svc.repo() != null) {
        payload.put("repositoryId", "repo-" + svc.repo());
      }
      if (svc.name().equals("sms-dispatcher")) {
        s.upsertAt(StubSources.SERVICE, svc.key(), payload, now.minus(STALE_SERVICE_AGE));
      } else {
        s.upsert(StubSources.SERVICE, svc.key(), payload);
      }
    }
    return s;
  }

  public static StubSource cmdb(Clock clock) {
    StubSource s = new StubSource(SourceSystem.CMDB, clock);
    Instant now = clock.instant();
    int n = 0;
    for (Host h : HOSTS) {
      Map<String, Object> payload =
          fields(
              "hostname", h.id() + "." + h.env() + ".example.org",
              "kind", h.kind(),
              "state", h.state(),
              "environment", h.env(),
              "ip", "10." + envNumber(h.env()) + ".0." + (++n),
              "os", "RHEL 9");
      if (h.kind().equals("PHYSICAL_SERVER")) {
        payload.put("serialNumber", "SN-" + h.id().toUpperCase());
      } else {
        payload.put("hypervisorRef", n % 2 == 0 ? "ps-esx-prod-01" : "ps-esx-prod-02");
      }
      if (h.id().equals("vm-dev-legacy-01")) {
        s.upsertAt(StubSources.COMPUTE_INSTANCE, h.id(), payload, now.minus(STALE_HOST_AGE));
      } else {
        s.upsert(StubSources.COMPUTE_INSTANCE, h.id(), payload);
      }
    }
    // Дубль vm-pay-01: тот же hostname в другом регистре, развёртывания на него не ссылаются (К-5а).
    s.upsert(
        StubSources.COMPUTE_INSTANCE,
        DUPLICATE_HOST,
        fields(
            "hostname", "VM-PAY-01.PROD.EXAMPLE.ORG",
            "kind", "VIRTUAL_MACHINE",
            "state", "RUNNING",
            "environment", "prod",
            "ip", "10.1.9.88",
            "os", "RHEL 9",
            "hypervisorRef", "ps-esx-prod-01"));
    return s;
  }

  public static StubSource deployMap(Clock clock) {
    StubSource s = new StubSource(SourceSystem.DEPLOY_MAP, clock);
    Instant now = clock.instant();
    s.upsert(DeployMapFormat.ENVIRONMENT, "dev", environment("Development", "DEV"));
    s.upsert(DeployMapFormat.ENVIRONMENT, "test", environment("Test", "TEST"));
    s.upsert(DeployMapFormat.ENVIRONMENT, "prod", environment("Production", "PROD"));
    for (Deployment d : DEPLOYMENTS) {
      Map<String, Object> payload = deployment(d, commit(d));
      if (d.key().equals("dep-sms-dispatcher-dev")) {
        s.upsertAt(DeployMapFormat.DEPLOYMENT, d.key(), payload, now.minus(STALE_HOST_AGE));
      } else {
        s.upsert(DeployMapFormat.DEPLOYMENT, d.key(), payload);
      }
    }
    Deployment tokenizerTest = new Deployment("card-tokenizer", "test", "1.2.1", List.of("vm-card-test-01"));
    s.upsert(DeployMapFormat.DEPLOYMENT, QUARANTINE_DEPLOYMENT, deployment(tokenizerTest, "not-a-commit"));
    return s;
  }

  /**
   * Payload развёртывания для сценариев: тот же {@code sourceId}, другая версия chart; commit считается по правилу
   * {@link #commit}. Так сценарий «передеплой» меняет {@code dep-card-gateway-prod} без знания формата.
   */
  public static Map<String, Object> redeployPayload(String deploymentKey, String chartVersion) {
    Deployment d = DEPLOYMENTS.stream().filter(x -> x.key().equals(deploymentKey)).findFirst().orElseThrow();
    Deployment next = new Deployment(d.service(), d.env(), chartVersion, d.hosts());
    return deployment(next, commit(next));
  }

  /**
   * Commit развёртывания: явный для {@code payments-api} PROD и TEST, иначе {@code sha1(<репозиторий>@<chartVersion>)}
   * в нижнем регистре; репозиторий сервиса без {@code repo-}, а при его отсутствии имя сервиса.
   */
  public static String commit(String deploymentKey, String chartVersion) {
    String explicit = EXPLICIT_COMMITS.get(deploymentKey);
    if (explicit != null) {
      return explicit;
    }
    Deployment d = DEPLOYMENTS.stream().filter(x -> x.key().equals(deploymentKey)).findFirst().orElseThrow();
    return sha1(repoName(d.service()) + "@" + chartVersion);
  }

  private static String commit(Deployment d) {
    return commit(d.key(), d.version());
  }

  static String sha1(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is not available", e);
    }
  }

  private static Service service(String name) {
    return SERVICES.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }

  /** Имя репозитория для правила commit'а: репозиторий сервиса в SCM, а без него имя сервиса. */
  private static String repoName(String service) {
    Service s = service(service);
    return s.repo() != null ? s.repo() : s.name();
  }

  private static String repoUrl(String group, String repo) {
    return "https://" + GIT_HOST + "/" + group + "/" + repo;
  }

  /** URL репозитория в аннотации: ssh в PROD, https в TEST и DEV; нормализуются в один и тот же {@code url}. */
  private static String annotationUrl(Deployment d) {
    Service s = service(d.service());
    String group = s.group();
    String repo;
    if (d.service().equals("partner-gateway")) {
      repo = "partner-gateway-v2"; // репозитория с таким адресом в SCM нет (К-8)
    } else {
      repo = repoName(d.service());
    }
    return d.env().equals("prod")
        ? "git@" + GIT_HOST + ":" + group + "/" + repo + ".git"
        : repoUrl(group, repo) + ".git";
  }

  private static long systemId(Service s) {
    return SYSTEMS.stream().filter(y -> y.eamId().equals(s.systemCode())).findFirst().map(ItSystem::id).orElse(NO_SYSTEM_ID);
  }

  private static Map<String, Object> deployment(Deployment d, String commit) {
    Service s = service(d.service());
    // К-9: Helm chart settlement-recon называет систему 5102 (Ledger), хотя сервис принадлежит 5104.
    long systemId = d.service().equals("settlement-recon") && d.env().equals("prod") ? 5102 : systemId(s);
    Map<String, Object> annotations =
        fields(
            DeployMapFormat.ANNOTATION_EAM_SYSTEM_ID, String.valueOf(systemId),
            DeployMapFormat.ANNOTATION_GIT_REPO_URL, annotationUrl(d),
            DeployMapFormat.ANNOTATION_GIT_COMMIT, commit);
    return fields(
        "format", DeployMapFormat.FORMAT_V1,
        "name", s.name(),
        "service", s.key(),
        "environment", d.env(),
        "chart", s.name(),
        "chartVersion", d.version(),
        DeployMapFormat.CHART_META,
        fields("name", s.name(), "version", d.version(), "appVersion", d.version(), "annotations", annotations),
        "hosts", d.hosts());
  }

  private static Map<String, Object> environment(String name, String envClass) {
    return fields("format", DeployMapFormat.FORMAT, "name", name, "class", envClass);
  }

  private static String dependencyKey(String[] d) {
    return "dep-" + d[0] + "-" + d[1];
  }

  private static Map<String, Object> dependency(String[] d) {
    boolean async = d.length > 2 && d[2].equals("ASYNC");
    String criticality = d.length > 2 && !async ? d[2] : null;
    // Асинхронные связи идут по AMQP (mobile-bff -> notification-gateway), остальные по HTTP.
    String protocol = !async ? "HTTP" : d[0].equals("mobile-bff") ? "AMQP" : "HTTP";
    Map<String, Object> m =
        fields("from", "svc-" + d[0], "to", "svc-" + d[1], "kind", async ? "ASYNC" : "SYNC", "protocol", protocol);
    if (criticality != null) {
      m.put("criticality", criticality);
    }
    return m;
  }

  private static Map<String, Object> platformAttrs(Platform p) {
    if (p.id() == EamApiSeed.PLATFORM_KUBERNETES) {
      return fields("name", p.name(), "status", "ACTIVE", "lifecycle", p.lifecycle(), "description",
          "Shared container runtime");
    }
    if (p.id() == EamApiSeed.PLATFORM_POSTGRES) {
      return fields("name", p.name(), "status", "ACTIVE", "lifecycle", p.lifecycle(), "description",
          "Managed relational database");
    }
    return fields("name", p.name(), "status", "ACTIVE", "lifecycle", p.lifecycle(), "description",
        "Virtual machines outside the container platform");
  }

  private static Map<String, Object> solutionAttrs(Solution o) {
    String text =
        o.id() == EamApiSeed.SOLUTION_CARD_PROCESSING ? "Card payments processing solution"
            : o.id() == EamApiSeed.SOLUTION_LEDGER_ENGINE ? "Double-entry ledger solution"
            : o.name() + " solution";
    return fields("name", o.name(), "status", "ACTIVE", "lifecycle", "PRODUCTION",
        "description", "Domain: " + o.domain() + ". " + text, "platform_id", o.platformId());
  }

  private static Map<String, Object> systemAttrs(ItSystem y) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", y.name());
    m.put("eam_id", y.eamId());
    m.put("status", "ACTIVE");
    m.put("criticality", y.criticality());
    m.put("description", "Domain: " + y.domain() + ". " + y.summary());
    m.put("product_id", y.productId());
    m.put("platform_id", y.platformId());
    m.put("ownerTeam", y.team());
    return m;
  }

  private static int envNumber(String env) {
    return switch (env) {
      case "prod" -> 1;
      case "test" -> 2;
      default -> 3;
    };
  }

  private static List<Deployment> deployments() {
    List<Deployment> d = new ArrayList<>();
    prod(d, "payments-api", "1.4.2", "vm-pay-01", "vm-pay-02");
    prod(d, "payments-router", "2.3.0", "vm-pay-01", "vm-pay-02");
    prod(d, "payments-orchestrator", "0.9.4", "vm-pay-03");
    prod(d, "payments-batch", "1.1.0", "vm-pay-03");
    prod(d, "ledger-api", "3.2.1", "vm-ledger-01", "vm-ledger-02");
    prod(d, "ledger-posting", "3.2.1", "vm-ledger-01", "vm-ledger-02");
    prod(d, "ledger-reporting", "3.1.0", "vm-legacy-app-01");
    prod(d, "card-gateway", "4.0.2", "vm-card-01", "vm-card-02");
    prod(d, "card-authorizer", "4.0.2", "vm-card-01", "vm-card-02");
    prod(d, "card-tokenizer", "1.2.0", "vm-card-02");
    prod(d, "settlement-engine", "2.0.0", "vm-settle-01");
    prod(d, "settlement-recon", "1.3.5", "vm-legacy-app-01");
    prod(d, "fx-rates", "1.0.7", "vm-settle-01");
    prod(d, "fraud-scoring", "5.1.0", "vm-risk-01", "vm-risk-02");
    prod(d, "fraud-rules", "5.1.0", "vm-risk-02", "vm-risk-03");
    prod(d, "aml-screening", "2.4.1", "vm-risk-03");
    prod(d, "aml-case-mgmt", "1.8.0", "vm-legacy-app-01");
    prod(d, "mobile-bff", "6.2.0", "vm-chan-01", "vm-chan-02");
    prod(d, "mobile-auth", "2.1.3", "vm-chan-02");
    prod(d, "ib-web", "4.5.0", "vm-chan-01");
    prod(d, "ib-api", "4.5.0", "vm-chan-01", "vm-chan-02");
    prod(d, "notification-gateway", "1.9.2", "vm-legacy-app-01");
    prod(d, "sms-dispatcher", "1.2.4", "vm-legacy-app-02");
    prod(d, "customer-profile-api", "3.0.1", "vm-data-01");
    prod(d, "customer-consent", "1.4.0", "vm-data-01", "vm-chan-03");
    prod(d, "legacy-statements", "0.8.0", "vm-legacy-app-02");
    prod(d, "partner-gateway", "0.5.0", "vm-chan-03");
    d.add(new Deployment("payments-api", "test", "1.4.3", List.of("vm-pay-test-01")));
    d.add(new Deployment("payments-router", "test", "2.3.1", List.of("vm-pay-test-01")));
    d.add(new Deployment("card-gateway", "test", "4.0.3", List.of("vm-card-test-01")));
    d.add(new Deployment("card-authorizer", "test", "4.0.3", List.of("vm-card-test-01")));
    d.add(new Deployment("ledger-api", "test", "3.2.2", List.of("vm-ledger-test-01")));
    d.add(new Deployment("ledger-posting", "test", "3.2.2", List.of("vm-ledger-test-01")));
    d.add(new Deployment("settlement-engine", "test", "2.0.1", List.of("vm-ledger-test-01")));
    d.add(new Deployment("fraud-scoring", "test", "5.2.0-rc1", List.of("vm-risk-test-01")));
    d.add(new Deployment("mobile-bff", "test", "6.3.0-rc1", List.of("vm-chan-test-01")));
    d.add(new Deployment("ib-api", "test", "4.5.1", List.of("vm-chan-test-01")));
    d.add(new Deployment("payments-api", "dev", "1.5.0-rc1", List.of("vm-dev-shared-01")));
    d.add(new Deployment("mobile-bff", "dev", "6.3.0-rc2", List.of("vm-dev-shared-01")));
    d.add(new Deployment("fraud-scoring", "dev", "5.2.0-rc2", List.of("vm-dev-shared-01")));
    d.add(new Deployment("sms-dispatcher", "dev", "1.2.4", List.of("vm-dev-legacy-01")));
    return List.copyOf(d);
  }

  private static void prod(List<Deployment> d, String service, String version, String... hosts) {
    d.add(new Deployment(service, "prod", version, List.of(hosts)));
  }

  private static List<Host> hosts() {
    List<Host> h = new ArrayList<>();
    h.add(new Host("ps-esx-prod-01", "prod", "PHYSICAL_SERVER", "RUNNING"));
    h.add(new Host("ps-esx-prod-02", "prod", "PHYSICAL_SERVER", "RUNNING"));
    for (String vm :
        List.of("vm-pay-01", "vm-pay-02", "vm-pay-03", "vm-card-01", "vm-card-02", "vm-ledger-01", "vm-ledger-02",
            "vm-settle-01", "vm-risk-01", "vm-risk-02", "vm-risk-03", "vm-chan-01", "vm-chan-02", "vm-chan-03",
            "vm-data-01", "vm-legacy-app-01")) {
      h.add(new Host(vm, "prod", "VIRTUAL_MACHINE", "RUNNING"));
    }
    h.add(new Host("vm-legacy-app-02", "prod", "VIRTUAL_MACHINE", "MAINTENANCE"));
    for (String vm :
        List.of("vm-pay-test-01", "vm-card-test-01", "vm-ledger-test-01", "vm-risk-test-01", "vm-chan-test-01")) {
      h.add(new Host(vm, "test", "VIRTUAL_MACHINE", "RUNNING"));
    }
    h.add(new Host("vm-dev-shared-01", "dev", "VIRTUAL_MACHINE", "RUNNING"));
    h.add(new Host("vm-dev-legacy-01", "dev", "VIRTUAL_MACHINE", "STOPPED"));
    return List.copyOf(h);
  }
}
