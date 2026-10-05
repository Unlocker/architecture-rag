package io.github.unlocker.archrag.integrationtests;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Criticality;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.normalizer.CanonicalMapper;
import io.github.unlocker.archrag.normalizer.CommandSink;
import io.github.unlocker.archrag.normalizer.ScmMapper;
import java.util.Set;

/**
 * SCM, присылающий {@code IT_SYSTEM}: в PoC такого пути в нормализаторе нет, а для сценариев provenance нужен
 * актив с записями двух источников. Остальные типы отдаёт стандартный {@link ScmMapper}.
 */
final class ScmItSystemMapper implements CanonicalMapper {

  private final ScmMapper standard = new ScmMapper();

  @Override
  public SourceSystemCode source() {
    return SourceSystemCode.SCM;
  }

  @Override
  public Set<String> supportedSchemaVersions() {
    return standard.supportedSchemaVersions();
  }

  @Override
  public void map(Input input, CommandSink sink) {
    if (!"IT_SYSTEM".equals(input.key().sourceType())) {
      standard.map(input, sink);
      return;
    }
    sink.add(
        new UpsertNode(
            input.record(),
            new ITSystem(
                (String) input.payload().get("name"),
                null,
                Criticality.valueOf((String) input.payload().get("criticality")),
                null)));
  }
}
