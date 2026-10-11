package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Criticality;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Platform;
import io.github.unlocker.archrag.canonicalmodel.node.Solution;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EAM. Два формата payload:
 *
 * <ul>
 *   <li>{@code ArchObject} реального API ({@code id}, {@code ver}, {@code datetime}, {@code attrs}) для
 *       {@code PLATFORM}, {@code SOLUTION} и {@code IT_SYSTEM}, ключ — целочисленный {@code id}
 *       ({@code docs/06-eam-integration.md}): узлы {@code Platform}/{@code Solution}/{@code ITSystem} и связи
 *       {@code USES_SOLUTION} ({@code attrs.solution}), {@code USES_PLATFORM} ({@code attrs.platform}),
 *       {@code OWNED_BY} ({@code attrs.ownerTeam}, допущение 9). {@code attrs.eam_id} становится
 *       {@code ITSystem.eamCode}: по нему SCM находит систему;
 *   <li>плоский payload старой заглушки: {@code TEAM}, {@code SERVICE_DEPENDENCY} и {@code IT_SYSTEM} (поля
 *       {@code name}, {@code status}, {@code criticality}, {@code description}, {@code ownerTeam},
 *       {@code ownerSince}, {@code dependsOn}). Плоский {@code IT_SYSTEM} без {@code attrs} приходит только из
 *       реплея старого журнала и {@code eamCode} не получает, чтобы alias SCM не залечился на устаревший узел.
 * </ul>
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

  private static final System.Logger LOG = System.getLogger(EamMapper.class.getName());

  static final String IT_SYSTEM = "IT_SYSTEM";
  static final String PLATFORM = "PLATFORM";
  static final String SOLUTION = "SOLUTION";
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
      case PLATFORM, SOLUTION -> mapArchObject(input, sink);
      case TEAM -> sink.add(new UpsertNode(input.record(), new Team(p.required("name"), p.optional("type").orElse(null))));
      case IT_SYSTEM -> {
        if (p.optionalObject("attrs").isPresent()) {
          mapArchObject(input, sink);
          return;
        }
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

  /** Таблица критичности EAM -> {@link Criticality} (допущение 2 {@code docs/06}); значения вне таблицы не угадываются. */
  private static final Map<String, Criticality> CRITICALITY =
      Map.of("HIGH", Criticality.HIGH, "MEDIUM", Criticality.MEDIUM, "LOW", Criticality.LOW);

  private static void mapArchObject(CanonicalMapper.Input input, CommandSink sink) {
    SourceKey key = input.key();
    Payload attrs =
        new Payload(input.payload())
            .optionalObject("attrs")
            .orElseThrow(() -> new NormalizationException("MISSING_REQUIRED_FIELD", "required field is missing: attrs"));
    switch (key.sourceType()) {
      case PLATFORM ->
          sink.add(
              new UpsertNode(
                  input.record(),
                  new Platform(
                      attrs.required("name"),
                      attrs.optional("type").orElse(null),
                      attrs.optional("version").orElse(null),
                      attrs.optional("lifecycle").orElse(null),
                      attrs.optional("description").orElse(null))));
      case SOLUTION -> {
        sink.add(
            new UpsertNode(
                input.record(),
                new Solution(
                    attrs.required("name"),
                    attrs.optional("lifecycle").orElse(null),
                    attrs.optional("description").orElse(null))));
        references(attrs, "platform", RelationType.USES_PLATFORM, key, NodeLabel.SOLUTION, PLATFORM, NodeLabel.PLATFORM, sink);
      }
      default -> {
        Optional<String> rawCriticality = attrs.optional("criticality");
        Criticality criticality = rawCriticality.map(v -> CRITICALITY.get(v.toUpperCase(Locale.ROOT))).orElse(null);
        String unmapped = criticality == null ? rawCriticality.orElse(null) : null;
        if (unmapped != null) {
          // Неизвестное значение словаря не отбрасывается: сырое значение сохраняется для разбора, в журнал — только код.
          sink.warn("UNMAPPED_CRITICALITY");
          LOG.log(System.Logger.Level.WARNING, "EAM IT_SYSTEM has a criticality value outside the dictionary");
        }
        sink.add(
            new UpsertNode(
                input.record(),
                new ITSystem(
                    attrs.required("name"),
                    attrs.optional("status").orElse(null),
                    criticality,
                    attrs.optional("description").orElse(null),
                    attrs.optional("eam_id").orElse(null),
                    unmapped)));
        references(attrs, "solution", RelationType.USES_SOLUTION, key, NodeLabel.IT_SYSTEM, SOLUTION, NodeLabel.SOLUTION, sink);
        attrs
            .optional("ownerTeam")
            .ifPresent(
                team ->
                    sink.relation(
                        RelationType.OWNED_BY,
                        key,
                        NodeLabel.IT_SYSTEM,
                        new SourceKey(SourceSystemCode.EAM, TEAM, team),
                        NodeLabel.TEAM,
                        attrs.optionalInstant("ownerSince").map(from -> new Validity(from, null)).orElse(null),
                        "ownerTeam"));
      }
    }
  }

  /** Связь на каждый {@code id} из поля-ссылки; неизвестная цель откладывается штатным {@code DeferRelation}. */
  private static void references(
      Payload attrs,
      String field,
      RelationType type,
      SourceKey from,
      NodeLabel fromLabel,
      String targetType,
      NodeLabel targetLabel,
      CommandSink sink) {
    for (String id : attrs.optionalIdRefs(field)) {
      sink.relation(type, from, fromLabel, new SourceKey(SourceSystemCode.EAM, targetType, id), targetLabel, null, field);
    }
  }
}
