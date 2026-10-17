package io.github.unlocker.archrag.sourcestubs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Контрактный тест демо-профиля: сверяет {@link DemoSources} с таблицами {@code docs/demo/dataset.md}. Документ —
 * источник истины, значения в тест не копируются: таблицы разбираются из файла, поэтому расхождение профиля с документом
 * (или правка документа без правки профиля) ломает сборку.
 */
class DemoDatasetContractTest {

  private static final Path DATASET = Path.of("../../docs/demo/dataset.md");
  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Pattern TICKS = Pattern.compile("`([^`]+)`");

  private static List<String> doc;
  private static Map<SourceSystem, StubSource> sources;
  private static EamApiStore eamApi;

  @BeforeAll
  static void load() throws IOException {
    doc = Files.readAllLines(DATASET);
    sources = DemoSources.seeded(CLOCK);
    eamApi = DemoSources.eamApi(CLOCK);
  }

  // ---- разбор markdown ------------------------------------------------------------------------

  /** Строки первой таблицы после строки, содержащей {@code marker} (без заголовка и разделителя); ячейки очищены от {@code **}. */
  private static List<List<String>> table(String marker) {
    int at = -1;
    for (int i = 0; i < doc.size() && at < 0; i++) {
      if (doc.get(i).contains(marker)) {
        at = i;
      }
    }
    assertThat(at).as("marker '%s' in dataset.md", marker).isGreaterThanOrEqualTo(0);
    int i = at + 1;
    while (!doc.get(i).startsWith("|")) {
      i++;
    }
    List<List<String>> rows = new ArrayList<>();
    for (i += 2; i < doc.size() && doc.get(i).startsWith("|"); i++) {
      String line = doc.get(i).strip();
      List<String> cells = new ArrayList<>();
      for (String c : line.substring(1, line.length() - 1).split("\\|", -1)) {
        cells.add(c.strip().replace("**", ""));
      }
      rows.add(cells);
    }
    return rows;
  }

  private static List<String> ticks(String cell) {
    Matcher m = TICKS.matcher(cell);
    List<String> out = new ArrayList<>();
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  /** Ключи репозиториев в ячейке (прочие значения в обратных кавычках, например имя связи в пояснении, не в счёт). */
  private static List<String> repositories(String cell) {
    return ticks(cell).stream().filter(t -> t.startsWith("repo-")).toList();
  }

  private static String first(String cell) {
    return ticks(cell).getFirst();
  }

  private static long firstNumber(String cell) {
    Matcher m = Pattern.compile("\\d+").matcher(cell);
    assertThat(m.find()).as(cell).isTrue();
    return Long.parseLong(m.group());
  }

  private static Map<String, Object> payload(SourceSystem system, String type, String id) {
    return sources.get(system).fetchById(type, id).orElseThrow(() -> new AssertionError(type + "/" + id)).payload();
  }

  private static List<SourceChange> snapshot(SourceSystem system, String type) {
    ChangePage page = sources.get(system).fetchChanges(null, 10_000);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.snapshotComplete()).isTrue();
    return page.changes().stream().filter(c -> c.sourceType().equals(type)).toList();
  }

  private static String norm(String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
  }

  // ---- масштаб --------------------------------------------------------------------------------

  @Test
  void scaleLineOfTheDocumentMatchesTheProfile() {
    String scale = doc.stream().filter(l -> l.contains("**Масштаб:**")).findFirst().orElseThrow();
    assertThat(count(scale, "бизнес-домена")).isEqualTo(domains().size());
    assertThat(count(scale, "ИТ-систем")).isEqualTo(snapshotEamApi(EamApiSeed.IT_SYSTEM_TYPE));
    assertThat(count(scale, "ИТ-решений")).isEqualTo(snapshotEamApi(EamApiSeed.SOLUTION_TYPE));
    assertThat(count(scale, "платформы")).isEqualTo(snapshotEamApi(EamApiSeed.PLATFORM_TYPE));
    assertThat(count(scale, "сервисов")).isEqualTo(snapshot(SourceSystem.SCM, StubSources.SERVICE).size());
    assertThat(count(scale, "репозиториев")).isEqualTo(snapshot(SourceSystem.SCM, StubSources.REPOSITORY).size());
    assertThat(count(scale, "записей CMDB")).isEqualTo(snapshot(SourceSystem.CMDB, StubSources.COMPUTE_INSTANCE).size());
    assertThat(count(scale, "команд")).isEqualTo(snapshot(SourceSystem.EAM, StubSources.TEAM).size());
    // Отдельная запись для карантина в число развёртываний не входит.
    assertThat(count(scale, "развёртывание"))
        .isEqualTo(snapshot(SourceSystem.DEPLOY_MAP, DeployMapFormat.DEPLOYMENT).size() - 1);
    assertThat(snapshot(SourceSystem.EAM, StubSources.SERVICE_DEPENDENCY)).hasSize(29);
  }

  private static int count(String line, String noun) {
    Matcher m = Pattern.compile("(\\d+) " + noun).matcher(line);
    assertThat(m.find()).as("'%s' in the scale line", noun).isTrue();
    return Integer.parseInt(m.group(1));
  }

  private static int snapshotEamApi(String type) {
    return eamApi.page(type, null, 1, 1000).size();
  }

  private static Set<String> domains() {
    return table("## 3. Домены").stream().map(r -> r.get(0)).collect(Collectors.toCollection(TreeSet::new));
  }

  // ---- EAM ------------------------------------------------------------------------------------

  @Test
  void eamPlatformsSolutionsAndSystemsMatchTheTables() {
    for (List<String> r : table("**Платформы**")) {
      var attrs = eamApi.find(EamApiSeed.PLATFORM_TYPE, firstNumber(r.get(0))).orElseThrow().attrs();
      assertThat(attrs).containsEntry("name", r.get(1)).containsEntry("lifecycle", r.get(2));
    }
    for (List<String> r : table("**Решения**")) {
      var attrs = eamApi.find(EamApiSeed.SOLUTION_TYPE, firstNumber(r.get(0))).orElseThrow().attrs();
      assertThat(attrs).containsEntry("name", r.get(1)).containsEntry("platform_id", firstNumber(r.get(2)));
      assertThat((String) attrs.get("description")).startsWith("Domain: " + r.get(3) + ".");
    }
    List<List<String>> systems = table("**ИТ-системы**");
    assertThat(systems).hasSize(10);
    for (List<String> r : systems) {
      var attrs = eamApi.find(EamApiSeed.IT_SYSTEM_TYPE, firstNumber(r.get(0))).orElseThrow().attrs();
      assertThat(attrs)
          .containsEntry("eam_id", first(r.get(1)))
          .containsEntry("name", r.get(2))
          .containsEntry("product_id", firstNumber(r.get(3)))
          .containsEntry("platform_id", firstNumber(r.get(4)))
          .containsEntry("criticality", Pattern.compile("[A-Z]+").matcher(r.get(5)).results().findFirst().orElseThrow().group())
          .containsEntry("ownerTeam", first(r.get(6)));
    }
    // Домен каждой системы разбирается шаблоном из раздела 8 (export-graph.sh).
    Pattern domain = Pattern.compile("^Domain: (Payments|Risk & Compliance|Customer Channels)\\.");
    for (List<String> r : table("## 3. Домены")) {
      long id = firstNumber(r.get(1));
      var attrs = eamApi.find(EamApiSeed.IT_SYSTEM_TYPE, id).orElseThrow().attrs();
      Matcher m = domain.matcher((String) attrs.get("description"));
      assertThat(m.find()).as("domain of %s", id).isTrue();
      assertThat(domainName(r.get(0))).isEqualTo(m.group(1));
    }
  }

  private static String domainName(String ru) {
    return switch (ru) {
      case "Платежи" -> "Payments";
      case "Риск и комплаенс" -> "Risk & Compliance";
      case "Клиентские каналы" -> "Customer Channels";
      default -> throw new AssertionError(ru);
    };
  }

  @Test
  void platformInvariantHoldsExceptForTheDocumentedCase() {
    Map<Long, Long> solutionPlatform = new HashMap<>();
    for (List<String> r : table("**Решения**")) {
      solutionPlatform.put(firstNumber(r.get(0)), firstNumber(r.get(2)));
    }
    List<Long> mismatched = new ArrayList<>();
    for (List<String> r : table("**ИТ-системы**")) {
      if (!solutionPlatform.get(firstNumber(r.get(3))).equals(firstNumber(r.get(4)))) {
        mismatched.add(firstNumber(r.get(0)));
      }
    }
    assertThat(mismatched).containsExactly(5303L);
  }

  @Test
  void teamsMatchTheTable() {
    List<List<String>> teams = table("**Команды**");
    assertThat(teams).hasSize(5);
    for (List<String> r : teams) {
      assertThat(payload(SourceSystem.EAM, StubSources.TEAM, first(r.get(0))))
          .containsEntry("name", r.get(1))
          .containsEntry("type", r.get(2));
    }
  }

  @Test
  void serviceDependenciesMatchTheTable() {
    Set<String> expected = new TreeSet<>();
    Set<String> high = new TreeSet<>();
    for (List<String> r : table("**Зависимости сервисов**")) {
      String from = first(r.get(0));
      Matcher m = Pattern.compile("`(svc-[a-z-]+)`( \\(HIGH)?").matcher(r.get(1));
      while (m.find()) {
        expected.add(from + ">" + m.group(1));
        if (m.group(2) != null) {
          high.add(from + ">" + m.group(1));
        }
      }
    }
    Set<String> actual = new TreeSet<>();
    Set<String> actualHigh = new TreeSet<>();
    for (SourceChange c : snapshot(SourceSystem.EAM, StubSources.SERVICE_DEPENDENCY)) {
      String edge = c.payload().get("from") + ">" + c.payload().get("to");
      actual.add(edge);
      if ("HIGH".equals(c.payload().get("criticality"))) {
        actualHigh.add(edge);
      }
      assertThat(c.sourceId()).isEqualTo("dep-" + ((String) c.payload().get("from")).substring(4) + "-"
          + ((String) c.payload().get("to")).substring(4));
    }
    assertThat(actual).hasSize(29).isEqualTo(expected);
    assertThat(actualHigh).isEqualTo(high);
    assertThat(payload(SourceSystem.EAM, StubSources.SERVICE_DEPENDENCY, "dep-mobile-bff-notification-gateway"))
        .containsEntry("kind", "ASYNC")
        .containsEntry("protocol", "AMQP");
  }

  // ---- SCM ------------------------------------------------------------------------------------

  /** Сервис -> репозиторий из таблицы 4.3 (без репозитория значение отсутствует). */
  private static Map<String, String> serviceRepository() {
    Map<String, String> m = new HashMap<>();
    for (List<String> r : table("### 4.3")) {
      List<String> repo = repositories(r.get(3));
      if (!repo.isEmpty()) {
        m.put(first(r.get(0)), repo.getFirst());
      }
    }
    return m;
  }

  @Test
  void scmServicesAndRepositoriesMatchTheTable() {
    List<List<String>> rows = table("### 4.3");
    assertThat(rows).hasSize(27);
    Set<String> repositories = new TreeSet<>();
    for (List<String> r : rows) {
      String key = first(r.get(0));
      var p = payload(SourceSystem.SCM, StubSources.SERVICE, key);
      assertThat(p).containsEntry("name", first(r.get(1))).containsEntry("systemCode", first(r.get(2)))
          .containsEntry("language", r.get(4));
      List<String> repo = repositories(r.get(3));
      if (repo.isEmpty()) {
        assertThat(p).doesNotContainKey("repositoryId");
        continue;
      }
      assertThat(p).containsEntry("repositoryId", repo.getFirst());
      repositories.add(repo.getFirst());
      String group = ticks(r.get(3).substring(r.get(3).indexOf('→'))).getFirst();
      assertThat(payload(SourceSystem.SCM, StubSources.REPOSITORY, repo.getFirst()))
          .containsEntry("url", "https://git.example.org/" + group + "/" + repo.getFirst().substring(5));
    }
    assertThat(repositories).hasSize(25);
    // Дубль репозитория: тот же адрес без учёта регистра (кейс К-5б).
    String url = (String) payload(SourceSystem.SCM, StubSources.REPOSITORY, "repo-payments-api").get("url");
    String dup = (String) payload(SourceSystem.SCM, StubSources.REPOSITORY, DemoSources.DUPLICATE_REPOSITORY).get("url");
    assertThat(dup).isNotEqualTo(url).isEqualToIgnoringCase(url);
  }

  @Test
  void namesAreUniqueWithinLabelSoNoUnexpectedIdentityCandidates() {
    for (SourceSystem s : List.of(SourceSystem.SCM)) {
      List<String> names =
          snapshot(s, StubSources.SERVICE).stream().map(c -> norm((String) c.payload().get("name"))).toList();
      assertThat(names).doesNotHaveDuplicates();
    }
    List<String> systemNames = new ArrayList<>();
    for (var e : eamApi.page(EamApiSeed.IT_SYSTEM_TYPE, null, 1, 1000)) {
      systemNames.add(norm((String) e.attrs().get("name")));
    }
    assertThat(systemNames).doesNotHaveDuplicates();
    assertThat(snapshot(SourceSystem.EAM, StubSources.TEAM).stream().map(c -> norm((String) c.payload().get("name"))))
        .doesNotHaveDuplicates();
  }

  // ---- CMDB -----------------------------------------------------------------------------------

  @Test
  void cmdbHostsMatchTheTable() {
    Set<String> ids = new TreeSet<>();
    for (List<String> r : table("### 4.4")) {
      for (String id : ticks(r.get(0))) {
        ids.add(id);
        var p = payload(SourceSystem.CMDB, StubSources.COMPUTE_INSTANCE, id);
        assertThat(p).containsEntry("state", r.get(3)).containsEntry("environment", r.get(1));
        assertThat(p.get("kind")).isEqualTo(r.get(2).equals("VM") ? "VIRTUAL_MACHINE" : r.get(2));
        if (!id.equals(DemoSources.DUPLICATE_HOST)) {
          assertThat(p).containsEntry("hostname", id + "." + r.get(1) + ".example.org");
        }
      }
    }
    assertThat(ids).hasSize(27);
    assertThat(snapshot(SourceSystem.CMDB, StubSources.COMPUTE_INSTANCE)).extracting(SourceChange::sourceId)
        .containsExactlyInAnyOrderElementsOf(ids);
    assertThat((String) payload(SourceSystem.CMDB, StubSources.COMPUTE_INSTANCE, DemoSources.DUPLICATE_HOST).get("hostname"))
        .isEqualToIgnoringCase((String) payload(SourceSystem.CMDB, StubSources.COMPUTE_INSTANCE, "vm-pay-01").get("hostname"))
        .isNotEqualTo(payload(SourceSystem.CMDB, StubSources.COMPUTE_INSTANCE, "vm-pay-01").get("hostname"));
  }

  // ---- deploy map -----------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void deploymentsMatchTheTablesAndTheCommitRule() {
    List<String> exceptions = new ArrayList<>();
    Matcher hex = Pattern.compile("`([0-9a-f]{40})`").matcher(String.join("\n", doc));
    while (hex.find()) {
      exceptions.add(hex.group(1));
    }
    // Исключения правила и пример правила названы в документе: payments-api PROD, TEST, затем rc1 по правилу.
    String prodCommit = exceptions.get(0);
    String testCommit = exceptions.get(1);
    String ruleExample = exceptions.get(2);
    assertThat(DemoSources.sha1("payments-api@1.5.0-rc1")).isEqualTo(ruleExample);

    Map<String, String> repos = serviceRepository();
    Set<String> keys = new TreeSet<>();
    for (String[] section : new String[][] {{"**PROD** (27", "prod"}, {"**TEST** (10", "test"}, {"**DEV** (4", "dev"}}) {
      for (List<String> r : table(section[0])) {
        String key = first(r.get(0));
        keys.add(key);
        String service = key.substring("dep-".length(), key.length() - section[1].length() - 1);
        Map<String, Object> p = payload(SourceSystem.DEPLOY_MAP, DeployMapFormat.DEPLOYMENT, key);
        assertThat(p).containsEntry("environment", section[1]).containsEntry("chartVersion", r.get(1))
            .containsEntry("service", "svc-" + service).containsEntry("chart", service)
            .containsEntry("hosts", ticks(r.get(2)).stream().filter(t -> t.matches("(vm|ps)-[a-z0-9-]+")).toList());
        Map<String, Object> annotations =
            (Map<String, Object>) ((Map<String, Object>) p.get(DeployMapFormat.CHART_META)).get("annotations");
        String repo = repos.containsKey("svc-" + service) ? repos.get("svc-" + service).substring(5) : service;
        String expected = switch (key) {
          case "dep-payments-api-prod" -> prodCommit;
          case "dep-payments-api-test" -> testCommit;
          default -> DemoSources.sha1(repo + "@" + r.get(1));
        };
        assertThat(annotations).containsEntry(DeployMapFormat.ANNOTATION_GIT_COMMIT, expected);
        assertThat((String) annotations.get(DeployMapFormat.ANNOTATION_GIT_REPO_URL))
            .startsWith(section[1].equals("prod") ? "git@git.example.org:" : "https://git.example.org/");
      }
    }
    assertThat(keys).hasSize(41);
    assertThat(snapshot(SourceSystem.DEPLOY_MAP, DeployMapFormat.DEPLOYMENT)).extracting(SourceChange::sourceId)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(keys.stream(), java.util.stream.Stream.of(DemoSources.QUARANTINE_DEPLOYMENT)).toList());
    assertThat(payload(SourceSystem.DEPLOY_MAP, DeployMapFormat.DEPLOYMENT, "dep-payments-api-dev").toString())
        .contains("1ffad212");
  }

  @Test
  @SuppressWarnings("unchecked")
  void documentedCasesAreWiredIntoTheData() {
    var recon = (Map<String, Object>) payload(SourceSystem.DEPLOY_MAP, DeployMapFormat.DEPLOYMENT, "dep-settlement-recon-prod")
        .get(DeployMapFormat.CHART_META);
    assertThat(((Map<String, Object>) recon.get("annotations"))).containsEntry(DeployMapFormat.ANNOTATION_EAM_SYSTEM_ID, "5102");
    assertThat(payload(SourceSystem.SCM, StubSources.SERVICE, "svc-settlement-recon")).containsEntry("systemCode", "EAM-2002");
    assertThat(eamApi.find(EamApiSeed.IT_SYSTEM_TYPE, 5201).orElseThrow().attrs()).containsEntry("criticality", "CRITICAL");
    var bad = (Map<String, Object>) payload(SourceSystem.DEPLOY_MAP, DeployMapFormat.DEPLOYMENT, DemoSources.QUARANTINE_DEPLOYMENT)
        .get(DeployMapFormat.CHART_META);
    assertThat(((Map<String, Object>) bad.get("annotations"))).containsEntry(DeployMapFormat.ANNOTATION_GIT_COMMIT, "not-a-commit");
    // К-6: всё, что исчезает во время сценария, есть в базовом seed.
    assertThat(sources.get(SourceSystem.SCM).fetchById(StubSources.SERVICE, DemoSources.RETIRED_SERVICE)).isPresent();
    assertThat(sources.get(SourceSystem.SCM).fetchById(StubSources.REPOSITORY, DemoSources.RETIRED_REPOSITORY)).isPresent();
    assertThat(sources.get(SourceSystem.EAM).fetchById(StubSources.SERVICE_DEPENDENCY, DemoSources.RETIRED_DEPENDENCY)).isPresent();
    assertThat(sources.get(SourceSystem.DEPLOY_MAP).fetchById(DeployMapFormat.DEPLOYMENT, DemoSources.RETIRED_DEPLOYMENT)).isPresent();
  }

  // ---- время и детерминизм --------------------------------------------------------------------

  @Test
  void onlyTheStaleRecordsAreOlderThanTheThreshold() {
    Map<String, Instant> old = new HashMap<>();
    for (SourceSystem s : SourceSystem.values()) {
      for (SourceChange c : sources.get(s).fetchChanges(null, 10_000).changes()) {
        if (!c.updatedAt().equals(NOW)) {
          old.put(c.sourceType() + "/" + c.sourceId(), c.updatedAt());
        }
      }
    }
    assertThat(old)
        .containsOnlyKeys("SERVICE/svc-sms-dispatcher", "DEPLOYMENT/dep-sms-dispatcher-dev", "COMPUTE_INSTANCE/vm-dev-legacy-01")
        .containsEntry("SERVICE/svc-sms-dispatcher", NOW.minus(DemoSources.STALE_SERVICE_AGE))
        .containsEntry("DEPLOYMENT/dep-sms-dispatcher-dev", NOW.minus(DemoSources.STALE_HOST_AGE))
        .containsEntry("COMPUTE_INSTANCE/vm-dev-legacy-01", NOW.minus(DemoSources.STALE_HOST_AGE));
    assertThat(DemoSources.STALE_SERVICE_AGE).isGreaterThan(java.time.Duration.ofDays(7));
    assertThat(DemoSources.STALE_HOST_AGE).isGreaterThan(java.time.Duration.ofDays(7));
  }

  @Test
  void seedIsDeterministicForTheSameClock() {
    var again = DemoSources.seeded(CLOCK);
    for (SourceSystem s : SourceSystem.values()) {
      assertThat(again.get(s).fetchChanges(null, 10_000).changes())
          .isEqualTo(sources.get(s).fetchChanges(null, 10_000).changes());
    }
  }

  @Test
  void redeployPayloadChangesVersionAndCommitByTheRule() {
    @SuppressWarnings("unchecked")
    var annotations = (Map<String, Object>) ((Map<String, Object>) DemoSources
        .redeployPayload("dep-card-gateway-prod", "4.0.3").get(DeployMapFormat.CHART_META)).get("annotations");
    assertThat(annotations).containsEntry(DeployMapFormat.ANNOTATION_GIT_COMMIT, DemoSources.sha1("card-gateway@4.0.3"));
  }
}
