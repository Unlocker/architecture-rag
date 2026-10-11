package io.github.unlocker.archrag.deploymapadapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.node.GitUrls;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.DeployMapFormat;
import io.github.unlocker.archrag.sourcestubs.EamApiFormat;
import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Контракт fixtures {@code src/test/resources/deploymap}: формат {@code deploymap-poc-1} с
 * {@code chartMeta}, согласованность с seed SCM/EAM заглушек и негативные сценарии.
 */
class DeployMapFixturesContractTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
  private static final String SYSTEM_ID = DeployMapFormat.ANNOTATION_EAM_SYSTEM_ID;
  private static final String REPO_URL = DeployMapFormat.ANNOTATION_GIT_REPO_URL;
  private static final String COMMIT = DeployMapFormat.ANNOTATION_GIT_COMMIT;

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> load(String name) {
    try (var in = DeployMapFixturesContractTest.class.getResourceAsStream("/deploymap/" + name)) {
      assertThat(in).as("fixture " + name).isNotNull();
      return (List<Map<String, Object>>)
          EamApiFormat.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payload(Map<String, Object> record) {
    return (Map<String, Object>) record.get("payload");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> meta(Map<String, Object> record) {
    return (Map<String, Object>) payload(record).get(DeployMapFormat.CHART_META);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> annotations(Map<String, Object> record) {
    return (Map<String, String>) meta(record).get("annotations");
  }

  private static Map<String, Object> single(String name) {
    List<Map<String, Object>> records = load(name);
    assertThat(records).hasSize(1);
    return records.get(0);
  }

  private static String scmRepositoryUrl() {
    SourceChange repo =
        StubSources.scm(CLOCK).fetchById(StubSources.REPOSITORY, "repo-payments-api").orElseThrow();
    return (String) repo.payload().get("url");
  }

  /** Система сервиса репозитория как {@code id} объекта EAM (через {@code eam_id = systemCode}). */
  private static String serviceSystemId() {
    SourceChange service =
        StubSources.scm(CLOCK).fetchById(StubSources.SERVICE, "svc-payments-api").orElseThrow();
    String systemCode = (String) service.payload().get("systemCode");
    SourceChange system =
        StubSources.eam(CLOCK).fetchById(StubSources.IT_SYSTEM, systemCode).orElseThrow();
    assertThat(system.sourceId()).isEqualTo(systemCode);
    // seed EamApiSeed: EAM-1042 <-> 5101, EAM-2001 <-> 5102
    return Map.of("EAM-1042", String.valueOf(EamApiSeed.SYSTEM_PAYMENTS_CORE), "EAM-2001",
            String.valueOf(EamApiSeed.SYSTEM_LEDGER)).get(systemCode);
  }

  @Test
  void everyPoc1FixtureHasChartMetaWithThreeAnnotations() {
    for (String name : List.of("baseline/deployments.json", "unknown-repository/deployments.json",
        "system-mismatch/deployments.json", "invalid-commit/deployments.json",
        "commit-change/deployments-v1.json", "commit-change/deployments-v2.json")) {
      for (Map<String, Object> record : load(name)) {
        assertThat(payload(record)).as(name).containsEntry("format", DeployMapFormat.FORMAT_V1);
        assertThat(meta(record)).as(name).containsKeys("name", "version", "appVersion");
        assertThat(meta(record).get("name")).isEqualTo(payload(record).get("chart"));
        assertThat(meta(record).get("version")).isEqualTo(payload(record).get("chartVersion"));
        assertThat(annotations(record)).as(name).containsOnlyKeys(SYSTEM_ID, REPO_URL, COMMIT);
      }
    }
  }

  @Test
  void baselineIsConsistentWithScmAndEamSeed() {
    Map<String, Object> record = single("baseline/deployments.json");
    Map<String, String> a = annotations(record);
    assertThat(a.get(COMMIT)).matches("[0-9a-f]{40}");
    assertThat(GitUrls.normalize(a.get(REPO_URL))).isEqualTo(scmRepositoryUrl());
    assertThat(a.get(SYSTEM_ID)).isEqualTo(serviceSystemId());
    assertThat(a.get(COMMIT)).isEqualTo(StubSources.PAYMENTS_API_COMMIT);
  }

  @Test
  void baselineMatchesStubSeed() {
    SourceChange seed = StubSources.deployMap(CLOCK)
        .fetchById(DeployMapFormat.DEPLOYMENT, "dep-payments-api-prod").orElseThrow();
    assertThat(payload(single("baseline/deployments.json"))).isEqualTo(seed.payload());
  }

  @Test
  void unknownRepositoryDoesNotMatchAnyScmRepository() {
    String normalized = GitUrls.normalize(annotations(single("unknown-repository/deployments.json")).get(REPO_URL));
    assertThat(normalized).isNotEqualTo(scmRepositoryUrl());
    assertThat(annotations(single("unknown-repository/deployments.json")).get(COMMIT)).matches("[0-9a-f]{40}");
  }

  @Test
  void systemMismatchKeepsRepositoryButChangesSystem() {
    Map<String, String> a = annotations(single("system-mismatch/deployments.json"));
    assertThat(GitUrls.normalize(a.get(REPO_URL))).isEqualTo(scmRepositoryUrl());
    assertThat(a.get(SYSTEM_ID)).isNotEqualTo(serviceSystemId())
        .isEqualTo(String.valueOf(EamApiSeed.SYSTEM_LEDGER));
  }

  @Test
  void invalidCommitIsNot40Hex() {
    assertThat(annotations(single("invalid-commit/deployments.json")).get(COMMIT))
        .doesNotMatch("[0-9a-f]{40}");
  }

  @Test
  void commitChangeKeepsSourceIdAndRaisesVersion() {
    Map<String, Object> v1 = single("commit-change/deployments-v1.json");
    Map<String, Object> v2 = single("commit-change/deployments-v2.json");
    assertThat(v2.get("sourceId")).isEqualTo(v1.get("sourceId"));
    assertThat((Long) v2.get("sourceVersion")).isGreaterThan((Long) v1.get("sourceVersion"));
    assertThat(annotations(v2).get(COMMIT)).matches("[0-9a-f]{40}")
        .isNotEqualTo(annotations(v1).get(COMMIT));
  }

  @Test
  void legacyPoc0HasNoChartMeta() {
    Map<String, Object> payload = payload(single("legacy-poc-0/deployments.json"));
    assertThat(payload).containsEntry("format", DeployMapFormat.FORMAT)
        .doesNotContainKey(DeployMapFormat.CHART_META)
        .containsKeys("chart", "chartVersion", "service", "environment", "hosts");
  }

  @Test
  void stubSystemIsDeployMap() {
    assertThat(StubSources.deployMap(CLOCK).system()).isEqualTo(SourceSystem.DEPLOY_MAP);
  }
}
