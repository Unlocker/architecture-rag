package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.GitUrls;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.provenance.LookupKeys;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.Set;

/**
 * SCM: {@code REPOSITORY} ({@code url}, {@code defaultBranch}, {@code archived}) и {@code SERVICE}
 * ({@code name}, {@code serviceType}, {@code language}, {@code status}, {@code systemCode} —
 * система EAM, {@code repositoryId}).
 */
public final class ScmMapper implements CanonicalMapper {

  static final String SERVICE = "SERVICE";
  static final String REPOSITORY = "REPOSITORY";

  private final DeploymentLookup lookup;

  public ScmMapper() {
    this(DeploymentLookup.NONE);
  }

  /** @param lookup порт графа: по нему {@code systemCode} находит ИТ-систему EAM с ключом {@code id} */
  public ScmMapper(DeploymentLookup lookup) {
    this.lookup = lookup;
  }

  @Override
  public SourceSystemCode source() {
    return SourceSystemCode.SCM;
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
      case REPOSITORY ->
          sink.add(
              new UpsertNode(
                  input.record(),
                  new Repository(
                      normalizedUrl(p.required("url")),
                      p.optional("defaultBranch").orElse(null),
                      p.optionalBoolean("archived").orElse(null))));
      case SERVICE -> {
        sink.add(
            new UpsertNode(
                input.record(),
                new Service(
                    p.required("name"),
                    p.optional("serviceType").orElse(null),
                    p.optional("language").orElse(null),
                    p.optional("status").orElse(null))));
        // Связь DECOMPOSED_INTO идёт от системы к сервису, но утверждает её запись сервиса. Ключ системы в EAM —
        // целочисленный id, а systemCode (eam_id) — её свойство: известную систему берём по коду, неизвестную
        // откладываем на alias-ключ, проектор достроит связь с приходом ITSystem с этим eamCode. На ключ
        // (EAM, IT_SYSTEM, <systemCode>) не ссылаемся никогда: такой записи в EAM нет.
        p.optional("systemCode")
            .ifPresent(
                system ->
                    sink.relation(
                        RelationType.DECOMPOSED_INTO,
                        lookup.itSystemByEamCode(system).orElseGet(() -> LookupKeys.itSystemCode(system)),
                        NodeLabel.IT_SYSTEM,
                        key,
                        NodeLabel.SERVICE,
                        validity(p),
                        "systemCode"));
        p.optional("repositoryId")
            .ifPresent(
                repo ->
                    sink.relation(
                        RelationType.IMPLEMENTED_IN,
                        key,
                        NodeLabel.SERVICE,
                        new SourceKey(SourceSystemCode.SCM, REPOSITORY, repo),
                        NodeLabel.REPOSITORY,
                        validity(p),
                        "repositoryId"));
      }
      default -> throw new NormalizationException("UNKNOWN_SOURCE_TYPE", "unknown SCM sourceType");
    }
  }

  /**
   * Приводит URL к каноническому виду ({@link GitUrls#normalize}), чтобы deploy map находил репозиторий по
   * равенству. Нераспознанный URL — {@code INVALID_PAYLOAD} без значения в причине.
   *
   * <p>Алиас сохраняет регистр пути; признак кандидата identity сравнивается без учёта регистра
   * ({@link GitUrls#matchKey}).
   */
  private static String normalizedUrl(String raw) {
    try {
      return GitUrls.normalize(raw);
    } catch (IllegalArgumentException e) {
      throw new NormalizationException("INVALID_PAYLOAD", "repository url is not a recognized git url");
    }
  }

  /** {@code validFrom} из источника; без него validity не задаётся, и проектор открывает связь на время события. */
  private static Validity validity(Payload p) {
    return p.optionalInstant("validFrom").map(from -> new Validity(from, null)).orElse(null);
  }
}
