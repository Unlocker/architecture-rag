package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeKind;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.util.Locale;
import java.util.Set;

/**
 * CMDB: {@code COMPUTE_INSTANCE} ({@code hostname}, {@code kind}, {@code ip}, {@code os},
 * {@code state}, {@code hypervisorRef}, {@code serialNumber}). {@code kind} обязателен (значение по
 * умолчанию затирало бы известный тип); поле {@code environment} не используется: привязку к окружению
 * задаёт deploy map.
 */
public final class CmdbMapper implements CanonicalMapper {

  static final String COMPUTE_INSTANCE = "COMPUTE_INSTANCE";

  @Override
  public SourceSystemCode source() {
    return SourceSystemCode.CMDB;
  }

  @Override
  public Set<String> supportedSchemaVersions() {
    return Set.of("1");
  }

  @Override
  public void map(Input input, CommandSink sink) {
    if (!COMPUTE_INSTANCE.equals(input.key().sourceType())) {
      throw new NormalizationException("UNKNOWN_SOURCE_TYPE", "unknown CMDB sourceType");
    }
    var p = new Payload(input.payload());
    // kind обязателен: подстановка UNSPECIFIED затёрла бы известный тип; частичный апдейт — E1.5.
    ComputeKind kind = kind(p.required("kind"));
    sink.add(
        new UpsertNode(
            input.record(),
            new ComputeInstance(
                p.required("hostname"),
                kind,
                p.optional("ip").orElse(null),
                p.optional("os").orElse(null),
                p.optional("state").orElse(null),
                p.optional("hypervisorRef").orElse(null),
                p.optional("serialNumber").orElse(null))));
  }

  private static ComputeKind kind(String value) {
    try {
      return ComputeKind.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new NormalizationException("INVALID_PAYLOAD", "field has unknown value: kind");
    }
  }
}
