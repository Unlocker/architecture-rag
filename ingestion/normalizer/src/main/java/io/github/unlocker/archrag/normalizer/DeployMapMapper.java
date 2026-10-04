package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.Environment;
import io.github.unlocker.archrag.canonicalmodel.node.EnvironmentClass;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.Locale;
import java.util.Set;

/**
 * Deploy map и Helm charts во ВРЕМЕННОМ формате {@code deploymap-poc-0} (после решения владельца
 * маппер заменяется): {@code ENVIRONMENT} ({@code name}, {@code class}) и {@code DEPLOYMENT}
 * ({@code name}, {@code service}, {@code environment}, {@code chart}, {@code chartVersion},
 * {@code hosts}, {@code validFrom} для связи {@code RUNS_ON}).
 *
 * <p>Класс окружения берётся из {@code class}, иначе выводится из кода окружения; неопознанный —
 * {@code INVALID_PAYLOAD}. Хосты ссылаются на {@code sourceId} записей CMDB.
 */
public final class DeployMapMapper implements CanonicalMapper {

  static final String ENVIRONMENT = "ENVIRONMENT";
  static final String DEPLOYMENT = "DEPLOYMENT";

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
        sink.add(
            new UpsertNode(
                input.record(),
                new Deployment(
                    key.sourceId(),
                    p.required("name"),
                    p.optional("chartVersion").orElse(null),
                    p.optional("status").orElse(null),
                    input.time())));
        p.optional("service")
            .ifPresent(
                service ->
                    sink.relation(
                        RelationType.HAS_DEPLOYMENT,
                        new SourceKey(SourceSystemCode.SCM, ScmMapper.SERVICE, service),
                        NodeLabel.SERVICE,
                        key,
                        NodeLabel.DEPLOYMENT,
                        null,
                        "service"));
        p.optional("environment")
            .ifPresent(
                env ->
                    sink.relation(
                        RelationType.IN_ENVIRONMENT,
                        key,
                        NodeLabel.DEPLOYMENT,
                        new SourceKey(SourceSystemCode.DEPLOYMAP, ENVIRONMENT, env),
                        NodeLabel.ENVIRONMENT,
                        null,
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

  /** {@code validFrom} из источника; без него validity не задаётся, решение остаётся за проектором (E1.5). */
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
