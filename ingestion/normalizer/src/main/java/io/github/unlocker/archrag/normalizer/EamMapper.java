package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Criticality;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EAM: {@code TEAM}, {@code SERVICE_DEPENDENCY}, {@code IT_SYSTEM} (поля {@code name}, {@code status}, {@code criticality},
 * {@code description}, {@code ownerTeam}, {@code ownerSince}, {@code dependsOn}).
 *
 * <p>{@code dependsOn} между IT-системами в модели не представим ({@code DEPENDS_ON} допустим
 * только {@code Service -> Service}); такие ссылки не выдаются, а попадают в предупреждение
 * {@code DEPENDS_ON_NOT_SUPPORTED}.
 *
 * <p>{@code SERVICE_DEPENDENCY} ({@code from}, {@code to} — коды сервисов SCM; {@code kind},
 * {@code protocol}, {@code criticality}, {@code validFrom} необязательны) даёт
 * {@code DEPENDS_ON Service -> Service}. В свойства связи попадают только переданные поля,
 * {@code validFrom} берётся только из источника.
 */
public final class EamMapper implements CanonicalMapper {

  static final String IT_SYSTEM = "IT_SYSTEM";
  static final String TEAM = "TEAM";
  static final String SERVICE_DEPENDENCY = "SERVICE_DEPENDENCY";

  @Override
  public SourceSystemCode source() {
    return SourceSystemCode.EAM;
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
      case TEAM -> sink.add(new UpsertNode(input.record(), new Team(p.required("name"), p.optional("type").orElse(null))));
      case IT_SYSTEM -> {
        sink.add(
            new UpsertNode(
                input.record(),
                new ITSystem(
                    p.required("name"),
                    p.optional("status").orElse(null),
                    p.optional("criticality").map(EamMapper::criticality).orElse(null),
                    p.optional("description").orElse(null))));
        p.optional("ownerTeam")
            .ifPresent(
                team ->
                    sink.relation(
                        RelationType.OWNED_BY,
                        key,
                        NodeLabel.IT_SYSTEM,
                        new SourceKey(SourceSystemCode.EAM, TEAM, team),
                        NodeLabel.TEAM,
                        p.optionalInstant("ownerSince").map(from -> new Validity(from, null)).orElse(null),
                        "ownerTeam"));
        if (!p.optionalList("dependsOn").isEmpty()) {
          sink.warn("DEPENDS_ON_NOT_SUPPORTED");
        }
      }
      case SERVICE_DEPENDENCY -> {
        Map<String, Object> props = new LinkedHashMap<>();
        p.optional("kind").ifPresent(v -> props.put("kind", v));
        p.optional("protocol").ifPresent(v -> props.put("protocol", v));
        p.optional("criticality").ifPresent(v -> props.put("criticality", criticality(v).name()));
        sink.link(
            RelationType.DEPENDS_ON,
            new SourceKey(SourceSystemCode.SCM, ScmMapper.SERVICE, p.required("from")),
            NodeLabel.SERVICE,
            new SourceKey(SourceSystemCode.SCM, ScmMapper.SERVICE, p.required("to")),
            NodeLabel.SERVICE,
            props,
            p.optionalInstant("validFrom").map(from -> new Validity(from, null)).orElse(null),
            "from",
            "to");
      }
      default -> throw new NormalizationException("UNKNOWN_SOURCE_TYPE", "unknown EAM sourceType");
    }
  }

  private static Criticality criticality(String value) {
    try {
      return Criticality.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new NormalizationException("INVALID_PAYLOAD", "field has unknown value: criticality");
    }
  }
}
