package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Каждый allowlist шаблонов — явное подмножество {@code NodeLabel} / {@code RelationType}. */
class AllowlistsSubsetTest {

  private static final List<String> ALL_LABELS = Arrays.stream(NodeLabel.values()).map(NodeLabel::label).toList();
  private static final List<String> ALL_RELATIONS = Arrays.stream(RelationType.values()).map(Enum::name).toList();

  @Test
  void labelAllowlistsAreSubsetsOfNodeLabel() {
    assertThat(AssetTemplates.SEARCHABLE_TYPES).isNotEmpty().isSubsetOf(ALL_LABELS);
    assertThat(DependencyTemplates.TRACE_LABELS).isNotEmpty().isSubsetOf(ALL_LABELS);
    assertThat(DependencyTemplates.IMPACT_TARGET_LABELS).isNotEmpty().isSubsetOf(ALL_LABELS);
    assertThat(ConsoleTemplates.STATS_LABELS).isNotEmpty().isSubsetOf(ALL_LABELS);
  }

  @Test
  void relationAllowlistsAreSubsetsOfRelationType() {
    assertThat(AssetTemplates.RELATION_TYPES).isNotEmpty().isSubsetOf(ALL_RELATIONS);
    assertThat(DependencyTemplates.TRACE_RELATION_TYPES).isNotEmpty().isSubsetOf(ALL_RELATIONS);
    assertThat(DependencyTemplates.IMPACT_RELATION_TYPES).isNotEmpty().isSubsetOf(ALL_RELATIONS);
    assertThat(ConsoleTemplates.STATS_RELATION_TYPES).isNotEmpty().isSubsetOf(ALL_RELATIONS);
    assertThat(ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES).isNotEmpty().isSubsetOf(ALL_RELATIONS);
  }

  @Test
  void allowlistsAreExplicitSubsetsNotAllEnumValues() {
    assertThat(AssetTemplates.SEARCHABLE_TYPES).hasSizeLessThan(ALL_LABELS.size());
    assertThat(AssetTemplates.RELATION_TYPES).hasSizeLessThan(ALL_RELATIONS.size());
  }
}
