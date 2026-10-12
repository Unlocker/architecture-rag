package io.github.unlocker.archrag.normalizer;

import static io.github.unlocker.archrag.normalizer.Fixtures.RAW;
import static io.github.unlocker.archrag.normalizer.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.provenance.LookupKeys;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.normalizer.NormalizationResult.Normalized;
import io.github.unlocker.archrag.normalizer.NormalizationResult.Quarantined;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Маппер deploy map: форматы {@code deploymap-poc-0}/{@code -1}, привязка к репозиторию и сервису, карантин. */
class DeployMapMapperTest {

  private static final String COMMIT = "9fceb02d0ae598e95dc970b74767f19372d61af8";
  private static final String URL = "https://git.example.org/pay/payments-api";
  private static final SourceKey REPO = new SourceKey(SourceSystemCode.SCM, "REPOSITORY", "repo-payments-api");
  private static final SourceKey PAY = new SourceKey(SourceSystemCode.SCM, "SERVICE", "svc-payments-api");
  private static final SourceKey LEDGER = new SourceKey(SourceSystemCode.SCM, "SERVICE", "svc-ledger-api");
  private static final SourceKey ALIAS = LookupKeys.repositoryUrl(URL);

  private final Set<SourceKey> known = new HashSet<>();
  private final Map<String, SourceKey> repositories = new HashMap<>();
  private final Map<SourceKey, List<SourceKey>> services = new HashMap<>();
  private final Normalizer normalizer =
      Normalizer.standard(
          known::contains,
          new DeploymentLookup() {
            @Override
            public Optional<SourceKey> repositoryByUrl(String normalizedUrl) {
              return Optional.ofNullable(repositories.get(normalizedUrl));
            }

            @Override
            public List<SourceKey> servicesImplementedIn(SourceKey repository) {
              return services.getOrDefault(repository, List.of());
            }
          });

  private void repositoryWith(SourceKey... implementing) {
    repositories.put(URL, REPO);
    services.put(REPO, List.of(implementing));
    known.add(REPO);
    known.addAll(List.of(implementing));
  }

  private static Map<String, Object> annotations(String system, String url, String commit) {
    Map<String, Object> a = new LinkedHashMap<>();
    if (system != null) {
      a.put("archrag.io/eam-system-id", system);
    }
    if (url != null) {
      a.put("archrag.io/git-repo-url", url);
    }
    if (commit != null) {
      a.put("archrag.io/git-commit", commit);
    }
    return a;
  }

  private static Map<String, Object> payloadV1(String service, Map<String, Object> annotations) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("format", "deploymap-poc-1");
    p.put("name", "payments-api");
    if (service != null) {
      p.put("service", service);
    }
    p.put("chartVersion", "1.4.2");
    p.put("chartMeta", Map.of("name", "payments-api", "version", "1.4.2", "annotations", annotations));
    return p;
  }

  private static Map<String, Object> goodAnnotations() {
    return annotations("5101", "git@git.example.org:pay/payments-api.git", COMMIT);
  }

  private NormalizationResult run(Map<String, Object> payload) {
    return normalizer.normalize(
        event("urn:corp:deploymap", "urn:corp:schema:asset-upserted:1", "DEPLOYMENT", "dep-1", payload), RAW);
  }

  private Normalized ok(Map<String, Object> payload) {
    var result = run(payload);
    assertThat(result).isInstanceOf(Normalized.class);
    return (Normalized) result;
  }

  private static <C extends GraphCommand> List<C> of(Normalized n, Class<C> type) {
    return n.commands().stream().filter(type::isInstance).map(type::cast).toList();
  }

  private static List<UpsertRelation> relations(Normalized n, RelationType type) {
    return of(n, UpsertRelation.class).stream().filter(r -> r.type() == type).toList();
  }

  // ---- позитив

  @Test
  void pocOneMapsCommitUrlSystemAndBindsRepositoryAndService() {
    repositoryWith(PAY);
    var n = ok(payloadV1("svc-payments-api", goodAnnotations()));

    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Deployment("dep-1", "payments-api", "1.4.2", null, COMMIT, URL, 5101L, Fixtures.T));
    assertThat(relations(n, RelationType.DEPLOYED_FROM))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.from()).isEqualTo(new SourceKey(SourceSystemCode.DEPLOYMAP, "DEPLOYMENT", "dep-1"));
              assertThat(r.to()).isEqualTo(REPO);
              assertThat(r.properties()).containsExactly(Map.entry("commit", COMMIT));
            });
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT))
        .singleElement()
        .satisfies(r -> assertThat(r.from()).isEqualTo(PAY));
    assertThat(n.unresolved()).isEmpty();
    assertThat(n.warnings()).isEmpty();
  }

  @Test
  void pocZeroKeepsLegacyBehaviourAndIgnoresChartMeta() {
    known.add(PAY);
    Map<String, Object> payload = payloadV1("svc-payments-api", goodAnnotations());
    payload.put("format", "deploymap-poc-0");
    var n = ok(payload);

    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Deployment("dep-1", "payments-api", "1.4.2", null, null, null, null, Fixtures.T));
    assertThat(relations(n, RelationType.DEPLOYED_FROM)).isEmpty();
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).hasSize(1);
  }

  @Test
  void recordWithoutFormatOrChartMetaIsLegacy() {
    known.add(PAY);
    var n = ok(Map.of("name", "payments-api", "service", "svc-payments-api"));
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).hasSize(1);
    assertThat(relations(n, RelationType.DEPLOYED_FROM)).isEmpty();
  }

  // ---- карантин

  @Test
  void missingAnnotationIsQuarantinedWithoutCommands() {
    for (var annotations :
        List.of(
            annotations(null, "git@git.example.org:pay/payments-api.git", COMMIT),
            annotations("5101", null, COMMIT),
            annotations("5101", "git@git.example.org:pay/payments-api.git", null),
            annotations("5101", "git@git.example.org:pay/payments-api.git", "  "))) {
      assertThat(run(payloadV1("svc-payments-api", annotations)))
          .isInstanceOfSatisfying(
              Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD"));
    }
  }

  @Test
  void invalidAnnotationValuesAreQuarantinedWithoutLeakingValues() {
    String secret = "token-s3cr3t";
    for (var annotations :
        List.of(
            annotations("5101", "git@git.example.org:pay/payments-api.git", COMMIT.toUpperCase()),
            annotations("5101", "git@git.example.org:pay/payments-api.git", COMMIT.substring(1)),
            annotations("5101", "git@git.example.org:pay/payments-api.git", COMMIT + secret),
            annotations("EAM-1042", "git@git.example.org:pay/payments-api.git", COMMIT),
            annotations("+5101", "git@git.example.org:pay/payments-api.git", COMMIT),
            annotations("0", "git@git.example.org:pay/payments-api.git", COMMIT),
            annotations("99999999999999999999", "git@git.example.org:pay/payments-api.git", COMMIT),
            annotations("5101", "ftp://" + secret + "@host/x", COMMIT),
            annotations("5101", secret, COMMIT))) {
      assertThat(run(payloadV1("svc-payments-api", annotations)))
          .isInstanceOfSatisfying(
              Quarantined.class,
              q -> {
                assertThat(q.errorCode()).isEqualTo("INVALID_PAYLOAD");
                assertThat(q.reason()).doesNotContain(secret).doesNotContain("EAM-1042").doesNotContain(COMMIT);
              });
    }
  }

  @Test
  void unknownFormatIsQuarantined() {
    Map<String, Object> payload = payloadV1("svc-payments-api", goodAnnotations());
    payload.put("format", "deploymap-poc-9");
    assertThat(run(payload))
        .isInstanceOfSatisfying(Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("INVALID_PAYLOAD"));
  }

  @Test
  void pocOneWithoutChartMetaIsQuarantined() {
    Map<String, Object> payload = payloadV1("svc-payments-api", goodAnnotations());
    payload.remove("chartMeta");
    assertThat(run(payload))
        .isInstanceOfSatisfying(Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD"));
    payload.put("chartMeta", Map.of("name", "payments-api"));
    assertThat(run(payload))
        .isInstanceOfSatisfying(Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD"));
  }

  /**
   * Fixture {@code system-mismatch}: система chart не совпадает с системой сервиса — маппер даёт обычную привязку и
   * ничего не сравнивает; конфликт открывает {@code EventProcessor} (H.5, ADR 0030).
   */
  @Test
  void systemMismatchGivesOrdinaryBindingWithoutConflictOrWarning() {
    repositoryWith(PAY);
    var n = ok(payloadV1(null, annotations("5102", "git@git.example.org:pay/payments-api.git", COMMIT)));
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Deployment("dep-1", "payments-api", "1.4.2", null, COMMIT, URL, 5102L, Fixtures.T));
    assertThat(relations(n, RelationType.DEPLOYED_FROM)).hasSize(1);
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).singleElement().satisfies(r -> assertThat(r.from()).isEqualTo(PAY));
    assertThat(n.warnings()).isEmpty();
    assertThat(n.unresolved()).isEmpty();
  }

  @Test
  void wrongChartMetaTypeIsQuarantined() {
    Map<String, Object> payload = payloadV1("svc-payments-api", goodAnnotations());
    payload.put("chartMeta", "not an object");
    assertThat(run(payload))
        .isInstanceOfSatisfying(Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("INVALID_PAYLOAD"));
  }

  // ---- неизвестный репозиторий

  @Test
  void unknownRepositoryDefersDeployedFromOnAliasAndKeepsServiceFallback() {
    known.add(PAY);
    var n = ok(payloadV1("svc-payments-api", goodAnnotations()));

    assertThat(n.unresolved())
        .singleElement()
        .satisfies(
            u -> {
              assertThat(u.relationType()).isEqualTo(RelationType.DEPLOYED_FROM);
              assertThat(u.target()).isEqualTo(ALIAS);
              assertThat(u.relation().properties()).containsEntry("commit", COMMIT);
            });
    assertThat(relations(n, RelationType.DEPLOYED_FROM)).isEmpty();
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).singleElement().satisfies(r -> assertThat(r.from()).isEqualTo(PAY));
  }

  @Test
  void unknownRepositoryWithoutServiceWarns() {
    var n = ok(payloadV1(null, goodAnnotations()));
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).isEmpty();
    assertThat(n.warnings()).containsExactly("DEPLOYMENT_SERVICE_UNRESOLVED");
  }

  // ---- выбор сервиса

  @Test
  void declaredServiceAmongRepositoryServicesWins() {
    repositoryWith(PAY, LEDGER);
    var n = ok(payloadV1("svc-ledger-api", goodAnnotations()));
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).singleElement().satisfies(r -> assertThat(r.from()).isEqualTo(LEDGER));
    assertThat(n.warnings()).isEmpty();
  }

  @Test
  void singleRepositoryServiceIsUsedEvenIfPayloadDisagreesOrIsMissing() {
    repositoryWith(PAY);
    for (String declared : new String[] {null, "svc-ledger-api"}) {
      var n = ok(payloadV1(declared, goodAnnotations()));
      assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).singleElement().satisfies(r -> assertThat(r.from()).isEqualTo(PAY));
    }
  }

  @Test
  void severalServicesWithoutDeclaredOneAreNotGuessed() {
    repositoryWith(PAY, LEDGER);
    var n = ok(payloadV1(null, goodAnnotations()));
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).isEmpty();
    assertThat(relations(n, RelationType.DEPLOYED_FROM)).hasSize(1);
    assertThat(n.warnings()).containsExactly("DEPLOYMENT_SERVICE_UNRESOLVED");
  }

  @Test
  void severalServicesWithForeignDeclaredServiceFallBackToPayloadService() {
    repositoryWith(PAY, LEDGER);
    known.add(new SourceKey(SourceSystemCode.SCM, "SERVICE", "svc-other"));
    var n = ok(payloadV1("svc-other", goodAnnotations()));
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT))
        .singleElement()
        .satisfies(r -> assertThat(r.from().sourceId()).isEqualTo("svc-other"));
  }

  @Test
  void repositoryWithoutServicesFallsBackToPayloadService() {
    repositoryWith();
    known.add(PAY);
    var n = ok(payloadV1("svc-payments-api", goodAnnotations()));
    assertThat(relations(n, RelationType.HAS_DEPLOYMENT)).singleElement().satisfies(r -> assertThat(r.from()).isEqualTo(PAY));
  }
}
