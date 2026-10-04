package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.Set;

/**
 * SCM: {@code REPOSITORY} ({@code url}, {@code defaultBranch}, {@code archived}) и {@code SERVICE}
 * ({@code name}, {@code serviceType}, {@code language}, {@code status}, {@code systemCode} —
 * система EAM, {@code repositoryId}).
 */
public final class ScmMapper implements CanonicalMapper {

  static final String SERVICE = "SERVICE";
  static final String REPOSITORY = "REPOSITORY";

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
                      p.required("url"),
                      p.optional("defaultBranch").orElse(null),
                      p.optionalBoolean("archived").orElse(false))));
      case SERVICE -> {
        sink.add(
            new UpsertNode(
                input.record(),
                new Service(
                    p.required("name"),
                    p.optional("serviceType").orElse(null),
                    p.optional("language").orElse(null),
                    p.optional("status").orElse(null))));
        // Связь DECOMPOSED_INTO идёт от системы к сервису, но утверждает её запись сервиса.
        p.optional("systemCode")
            .ifPresent(
                system ->
                    sink.relation(
                        RelationType.DECOMPOSED_INTO,
                        new SourceKey(SourceSystemCode.EAM, EamMapper.IT_SYSTEM, system),
                        NodeLabel.IT_SYSTEM,
                        key,
                        NodeLabel.SERVICE,
                        null,
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
                        null,
                        "repositoryId"));
      }
      default -> throw new NormalizationException("UNKNOWN_SOURCE_TYPE", "unknown SCM sourceType");
    }
  }
}
