package io.github.unlocker.archrag.graphprojector.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Neo4jSchemaTest {

  private static final Pattern NAME = Pattern.compile("^CREATE (?:CONSTRAINT|FULLTEXT INDEX) (\\w+) ");

  @Test
  void everyCanonicalLabelHasGidConstraint() {
    List<String> statements = Neo4jSchema.statements();
    for (NodeLabel label : NodeLabel.canonical()) {
      assertThat(statements)
          .as(label.label())
          .anyMatch(s -> s.contains("(n:" + label.label() + ")") && s.endsWith("REQUIRE n.gid IS UNIQUE"));
    }
  }

  @Test
  void gidConstraintNamesMatchFixedList() {
    assertThat(NodeLabel.canonical().stream().sorted(java.util.Comparator.comparingInt(NodeLabel::ordinal))
        .map(Neo4jSchema::gidConstraintName).toList())
        .containsExactly("it_system_gid", "service_gid", "repository_gid", "team_gid", "environment_gid", "deployment_gid", "compute_instance_gid", "virtual_machine_gid", "physical_server_gid", "namespace_gid", "kubernetes_cluster_gid");
  }

  @Test
  void gidConstraintNamesAreSnakeCase() {
    assertThat(Neo4jSchema.gidConstraintName(NodeLabel.IT_SYSTEM)).isEqualTo("it_system_gid");
    assertThat(Neo4jSchema.gidConstraintName(NodeLabel.VIRTUAL_MACHINE)).isEqualTo("virtual_machine_gid");
    assertThat(Neo4jSchema.gidConstraintName(NodeLabel.SERVICE)).isEqualTo("service_gid");
  }

  @Test
  void provenanceAndEnvironmentConstraintsPresent() {
    assertThat(Neo4jSchema.statements()).anyMatch(s -> s.contains("source_record_key")
        && s.contains("(n.source, n.sourceType, n.sourceId) IS UNIQUE"));
    assertThat(Neo4jSchema.statements()).anyMatch(s -> s.contains("source_system_code") && s.contains("n.code IS UNIQUE"));
    assertThat(Neo4jSchema.statements()).anyMatch(s -> s.contains("sync_run_id") && s.contains("n.runId IS UNIQUE"));
    assertThat(Neo4jSchema.statements()).anyMatch(s -> s.contains("environment_code") && s.contains("n.code IS UNIQUE"));
    assertThat(Neo4jSchema.statements()).anyMatch(s -> s.contains("asset_text") && s.contains("FOR (n:ITSystem|Service)"));
  }

  @Test
  void everyStatementIsIdempotent() {
    assertThat(Neo4jSchema.statements()).allMatch(s -> s.contains(" IF NOT EXISTS "));
  }

  @Test
  void noEnterpriseOrOutOfScopeFeatures() {
    assertThat(Neo4jSchema.statements())
        .noneMatch(s -> s.contains("NODE KEY") || s.contains("IS NOT NULL") || s.contains("VECTOR")
            || s.contains("environmentKey"));
  }

  @Test
  void objectNamesAreUnique() {
    List<String> names = Neo4jSchema.statements().stream().map(s -> {
      Matcher m = NAME.matcher(s);
      assertThat(m.find()).as(s).isTrue();
      return m.group(1);
    }).toList();
    assertThat(names).doesNotHaveDuplicates();
  }
}
