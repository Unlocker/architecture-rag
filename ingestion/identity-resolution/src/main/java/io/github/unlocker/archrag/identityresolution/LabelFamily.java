package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;

/** Семейство меток, внутри которого сравниваются признаки: подтипы {@code ComputeInstance} — одно семейство, остальные метки — сами. */
public final class LabelFamily {

  private LabelFamily() {}

  public static NodeLabel of(NodeLabel label) {
    return label.isA(NodeLabel.COMPUTE_INSTANCE) ? NodeLabel.COMPUTE_INSTANCE : label;
  }
}
