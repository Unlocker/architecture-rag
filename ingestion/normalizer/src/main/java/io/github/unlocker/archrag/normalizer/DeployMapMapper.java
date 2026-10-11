package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.Environment;
import io.github.unlocker.archrag.canonicalmodel.node.EnvironmentClass;
import io.github.unlocker.archrag.canonicalmodel.node.GitUrls;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.LookupKeys;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deploy map и Helm charts во ВРЕМЕННОМ формате {@code deploymap-poc-0} (после решения владельца
 * маппер заменяется): {@code ENVIRONMENT} ({@code name}, {@code class}) и {@code DEPLOYMENT}
 * ({@code name}, {@code service}, {@code environment}, {@code chart}, {@code chartVersion},
 * {@code hosts}, {@code validFrom} для связи {@code RUNS_ON}).
 *
 * <p>Класс окружения берётся из {@code class}, иначе выводится из кода окружения; неопознанный —
 * {@code INVALID_PAYLOAD}. Хосты ссылаются на {@code sourceId} записей CMDB.
 *
 * <p>Формат {@code deploymap-poc-1} ({@code format} в payload; событийная {@code schemaVersion} остаётся
 * {@code 1}) добавляет у {@code DEPLOYMENT} метаданные {@code chartMeta.annotations}: {@code
 * archrag.io/eam-system-id}, {@code archrag.io/git-repo-url}, {@code archrag.io/git-commit}. Нужны все три
 * (иначе {@code MISSING_REQUIRED_FIELD}); неверный формат значения или неизвестный {@code format} —
 * {@code INVALID_PAYLOAD}, причина без значений. Формат {@code poc-0} и запись без {@code format}
 * разбираются по-старому; {@code poc-1} без {@code chartMeta} — {@code MISSING_REQUIRED_FIELD}.
 *
 * <p>Состояния нет: граф читается только через порт {@link DeploymentLookup}. Выбор сервиса для
 * {@code HAS_DEPLOYMENT} — снимок на момент нормализации: (1) {@code service} из payload, если он среди
 * сервисов репозитория; (2) единственный сервис репозитория; (3) {@code service} из payload как есть (отложенная
 * связь при неизвестном ключе); (4) иначе связи нет и пишется предупреждение {@code DEPLOYMENT_SERVICE_UNRESOLVED}.
 * Переоценки при переносе репозитория к другому сервису нет до следующей версии записи или rebuild.
 */
public final class DeployMapMapper implements CanonicalMapper {

  static final String ENVIRONMENT = "ENVIRONMENT";
  static final String DEPLOYMENT = "DEPLOYMENT";

  static final String FORMAT_V0 = "deploymap-poc-0";
  static final String FORMAT_V1 = "deploymap-poc-1";
  static final String CHART_META = "chartMeta";
  static final String ANNOTATION_EAM_SYSTEM_ID = "archrag.io/eam-system-id";
  static final String ANNOTATION_GIT_REPO_URL = "archrag.io/git-repo-url";
  static final String ANNOTATION_GIT_COMMIT = "archrag.io/git-commit";

  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");

  private final DeploymentLookup lookup;

  public DeployMapMapper() {
    this(DeploymentLookup.NONE);
  }

  public DeployMapMapper(DeploymentLookup lookup) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
  }

  @Override
  public SourceSystemCode source() {
    return SourceSystemCode.DEPLOYMAP;
  }

  @Override
  public Set<String> supportedSchemaVersions() {
    return Set.of("1");
  }

  @Override
  public void map(Input input, CommandSink sink) {
    var p = new Payload(input.payload());
    SourceKey key = input.key();
    switch (key.sourceType()) {
      case ENVIRONMENT -> {
        String code = key.sourceId();
        String classText = p.optional("class").orElse(code);
        sink.add(
            new UpsertNode(
                input.record(),
                new Environment(code, p.required("name"), environmentClass(classText))));
      }
      case DEPLOYMENT -> {
        ChartMeta chart = chartMeta(p);
        sink.add(
            new UpsertNode(
                input.record(),
                new Deployment(
                    key.sourceId(),
                    p.required("name"),
                    p.optional("chartVersion").orElse(null),
                    p.optional("status").orElse(null),
                    chart == null ? null : chart.commit(),
                    chart == null ? null : chart.repositoryUrl(),
                    chart == null ? null : chart.eamSystemId(),
                    input.time())));
        if (chart == null) {
          p.optional("service")
              .ifPresent(service -> payloadService(service, key, validity(p), sink));
        } else {
          bindToRepository(chart, p, key, validity(p), sink);
        }
        p.optional("environment")
            .ifPresent(
                env ->
                    sink.relation(
                        RelationType.IN_ENVIRONMENT,
                        key,
                        NodeLabel.DEPLOYMENT,
                        new SourceKey(SourceSystemCode.DEPLOYMAP, ENVIRONMENT, env),
                        NodeLabel.ENVIRONMENT,
                        validity(p),
                        "environment"));
        for (String host : p.optionalList("hosts")) {
          sink.relation(
              RelationType.RUNS_ON,
              key,
              NodeLabel.DEPLOYMENT,
              new SourceKey(SourceSystemCode.CMDB, CmdbMapper.COMPUTE_INSTANCE, host),
              NodeLabel.COMPUTE_INSTANCE,
              validity(p),
              "hosts");
        }
      }
      default -> throw new NormalizationException("UNKNOWN_SOURCE_TYPE", "unknown deploymap sourceType");
    }
  }

  /** Три аннотации chart, уже проверенные; {@code repositoryUrl} нормализован. */
  private record ChartMeta(long eamSystemId, String repositoryUrl, String commit) {}

  /** Разбирает {@code chartMeta} формата {@code poc-1}; {@code null} — разбор по-старому (poc-0 или без {@code format}). */
  private static ChartMeta chartMeta(Payload p) {
    String format = p.optional("format").orElse(null);
    if (format != null && !format.equals(FORMAT_V0) && !format.equals(FORMAT_V1)) {
      throw new NormalizationException("INVALID_PAYLOAD", "unknown deploymap format");
    }
    if (!FORMAT_V1.equals(format)) {
      return null;
    }
    Optional<Payload> meta = p.optionalObject(CHART_META);
    if (meta.isEmpty()) {
      throw new NormalizationException("MISSING_REQUIRED_FIELD", "required field is missing: " + CHART_META);
    }
    Payload annotations = meta.get().optionalObject("annotations").orElseGet(() -> new Payload(Map.of()));
    String systemId = annotations.required(ANNOTATION_EAM_SYSTEM_ID);
    String repoUrl = annotations.required(ANNOTATION_GIT_REPO_URL);
    String commit = annotations.required(ANNOTATION_GIT_COMMIT);
    // Причины называют аннотацию, но не её значение: значения источника недоверенные.
    if (!COMMIT.matcher(commit).matches()) {
      throw invalidAnnotation(ANNOTATION_GIT_COMMIT);
    }
    return new ChartMeta(systemId(systemId), normalizedUrl(repoUrl), commit);
  }

  /** Положительное целое из цифр без знака; переполнение {@code long} — тоже неверный формат. */
  private static long systemId(String text) {
    if (DIGITS.matcher(text).matches()) {
      try {
        long id = Long.parseLong(text);
        if (id > 0) {
          return id;
        }
      } catch (NumberFormatException e) {
        throw invalidAnnotation(ANNOTATION_EAM_SYSTEM_ID);
      }
    }
    throw invalidAnnotation(ANNOTATION_EAM_SYSTEM_ID);
  }

  private static String normalizedUrl(String raw) {
    try {
      return GitUrls.normalize(raw);
    } catch (IllegalArgumentException e) {
      throw invalidAnnotation(ANNOTATION_GIT_REPO_URL);
    }
  }

  private static NormalizationException invalidAnnotation(String annotation) {
    return new NormalizationException("INVALID_PAYLOAD", "annotation has invalid format: " + annotation);
  }

  /** {@code DEPLOYED_FROM} к репозиторию и {@code HAS_DEPLOYMENT} к сервису, выбранному по правилам из javadoc класса. */
  private void bindToRepository(ChartMeta chart, Payload p, SourceKey key, Validity validity, CommandSink sink) {
    Optional<SourceKey> repository = lookup.repositoryByUrl(chart.repositoryUrl());
    // Неизвестный репозиторий: связь откладывается на alias-ключ по URL, проектор достроит её с приходом SCM.
    SourceKey target =
        repository.orElseGet(
            () -> LookupKeys.repositoryUrl(chart.repositoryUrl()));
    sink.relation(
        RelationType.DEPLOYED_FROM,
        key,
        NodeLabel.DEPLOYMENT,
        target,
        NodeLabel.REPOSITORY,
        Map.of("commit", chart.commit()),
        validity,
        "git-repo-url");

    Optional<SourceKey> declared =
        p.optional("service").map(service -> new SourceKey(SourceSystemCode.SCM, ScmMapper.SERVICE, service));
    List<SourceKey> services = repository.map(lookup::servicesImplementedIn).orElse(List.of());
    if (declared.isPresent() && services.contains(declared.get())) {
      sink.relation(
          RelationType.HAS_DEPLOYMENT, declared.get(), NodeLabel.SERVICE, key, NodeLabel.DEPLOYMENT, validity, "service");
    } else if (services.size() == 1) {
      sink.relation(
          RelationType.HAS_DEPLOYMENT, services.get(0), NodeLabel.SERVICE, key, NodeLabel.DEPLOYMENT, validity,
          "git-repo-url");
    } else if (declared.isPresent()) {
      payloadService(declared.get().sourceId(), key, validity, sink);
    } else {
      // Несколько сервисов без указания в payload (или ни одного): сервис не угадывается.
      sink.warn("DEPLOYMENT_SERVICE_UNRESOLVED");
    }
  }

  /** Старая семантика {@code service}: связь, а при неизвестном ключе отложенная ссылка. */
  private static void payloadService(String service, SourceKey key, Validity validity, CommandSink sink) {
    sink.relation(
        RelationType.HAS_DEPLOYMENT,
        new SourceKey(SourceSystemCode.SCM, ScmMapper.SERVICE, service),
        NodeLabel.SERVICE,
        key,
        NodeLabel.DEPLOYMENT,
        validity,
        "service");
  }

  /** {@code validFrom} из источника для всех темпоральных связей записи; без него validity не задаётся, и проектор открывает связь на время события (E1.5). */
  private static Validity validity(Payload p) {
    return p.optionalInstant("validFrom").map(from -> new Validity(from, null)).orElse(null);
  }

  private static EnvironmentClass environmentClass(String text) {
    return switch (text.toLowerCase(Locale.ROOT)) {
      case "dev", "development" -> EnvironmentClass.DEV;
      case "test", "qa" -> EnvironmentClass.TEST;
      case "preprod", "stage", "staging" -> EnvironmentClass.PREPROD;
      case "prod", "production" -> EnvironmentClass.PROD;
      default -> throw new NormalizationException("INVALID_PAYLOAD", "cannot determine environment class");
    };
  }
}
