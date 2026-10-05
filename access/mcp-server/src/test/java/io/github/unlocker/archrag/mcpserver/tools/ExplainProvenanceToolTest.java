package io.github.unlocker.archrag.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExplainProvenanceToolTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  private final GraphQueries queries = mock(GraphQueries.class);
  private final QueryLimits limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
  private ExplainProvenanceTool tool;

  @BeforeEach
  void setUp() {
    tool = new ExplainProvenanceTool(queries, limits);
  }

  private static Map<String, Object> record(String source, String authority, boolean active, List<String> conflicts) {
    Map<String, Object> r = new HashMap<>();
    r.put("source", source);
    r.put("sourceType", "IT_SYSTEM");
    r.put("sourceId", "id-1");
    r.put("sourceVersion", "1");
    r.put("fetchedAt", "2026-01-01T00:00:00Z");
    r.put("contentHash", "sha256:abc");
    r.put("active", active);
    r.put("deletedAt", active ? null : "2026-01-02T00:00:00Z");
    r.put("authority", authority);
    r.put("confidence", 1.0);
    r.put("conflicts", conflicts);
    return r;
  }

  private void graph(String type, List<Map<String, Object>> records) {
    Map<String, Object> row = new HashMap<>();
    row.put("gid", GID);
    row.put("type", type);
    row.put("isCurrent", true);
    row.put("deletedAt", null);
    row.put("records", records);
    when(queries.execute(eq("explain_provenance"), any(), any()))
        .thenReturn(new QueryResult("explain_provenance", List.of(row), false, 1, Duration.ZERO));
  }

  private void twoSources() {
    graph(
        "ITSystem",
        List.of(
            record("EAM", "MASTER", true, null),
            record("SCM", "SUPPLEMENTARY", true, List.of("criticality"))));
  }

  @Test
  void marksAuthoritativeSourceAndOpenConflict() {
    twoSources();

    ExplainProvenanceResult r = tool.explainProvenance(GID, null);

    assertThat(r.authorities()).containsExactly("EAM");
    assertThat(r.conflictState()).isEqualTo("OPEN");
    assertThat(r.property()).isNull();
    assertThat(r.records()).hasSize(2);
    assertThat(r.records().get(0)).satisfies(m -> {
      assertThat(m.source()).isEqualTo("EAM");
      assertThat(m.authoritative()).isTrue();
      assertThat(m.conflictProperties()).isEmpty();
      assertThat(m.contentHash()).isEqualTo("sha256:abc");
      assertThat(m.confidence()).isEqualTo(1.0);
    });
    assertThat(r.records().get(1)).satisfies(d -> {
      assertThat(d.authoritative()).isFalse();
      assertThat(d.authority()).isEqualTo("SUPPLEMENTARY");
      assertThat(d.conflictProperties()).containsExactly("criticality");
    });
  }

  @Test
  void propertyFiltersConflictsAndUsesPropertyAuthorities() {
    twoSources();

    ExplainProvenanceResult other = tool.explainProvenance(GID, "name");
    assertThat(other.property()).isEqualTo("name");
    assertThat(other.conflictState()).isEqualTo("NONE");
    assertThat(other.records()).allSatisfy(x -> assertThat(x.conflictProperties()).isEmpty());

    ExplainProvenanceResult same = tool.explainProvenance(GID, " criticality ");
    assertThat(same.property()).isEqualTo("criticality");
    assertThat(same.conflictState()).isEqualTo("OPEN");
    assertThat(same.records().get(1).conflictProperties()).containsExactly("criticality");
  }

  @Test
  void propertyRuleOverridesNodeAuthorities() {
    // Для Service мастер узла и свойства language — SCM; EAM не авторитетен ни там, ни там.
    graph("Service", List.of(record("SCM", "MASTER", true, null), record("EAM", "SUPPLEMENTARY", true, null)));

    ExplainProvenanceResult r = tool.explainProvenance(GID, "language");

    assertThat(r.authorities()).containsExactly("SCM");
    assertThat(r.records()).extracting(ExplainProvenanceResult.RecordRef::authoritative).containsExactly(true, false);
  }

  @Test
  void unknownLabelHasNoAuthorities() {
    graph("Mystery", List.of(record("EAM", "MASTER", true, null)));

    ExplainProvenanceResult r = tool.explainProvenance(GID, null);

    assertThat(r.authorities()).isEmpty();
    assertThat(r.records()).singleElement().satisfies(x -> assertThat(x.authoritative()).isFalse());
  }

  @Test
  void tombstoneRecordIsReturnedInactive() {
    graph("ITSystem", List.of(record("EAM", "MASTER", true, null), record("SCM", "SUPPLEMENTARY", false, null)));

    ExplainProvenanceResult r = tool.explainProvenance(GID, null);

    assertThat(r.conflictState()).isEqualTo("NONE");
    assertThat(r.records().get(1).active()).isFalse();
    assertThat(r.records().get(1).deletedAt()).isEqualTo("2026-01-02T00:00:00Z");
  }

  @Test
  void rejectsInvalidGidAndPropertyWithoutEchoOrGraphAccess() {
    assertThatThrownBy(() -> tool.explainProvenance("secret-not-uuid", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("gid must be a UUID");
    assertThatThrownBy(() -> tool.explainProvenance(null, null)).isInstanceOf(IllegalArgumentException.class);
    for (String bad : List.of("1abc", "a-b", "a b", "x'}) DETACH DELETE n //", "a".repeat(65))) {
      assertThatThrownBy(() -> tool.explainProvenance(GID, bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property is invalid");
    }
    verify(queries, never()).execute(any(), any(), any());
  }

  @Test
  void blankPropertyIsTreatedAsAbsent() {
    twoSources();

    assertThat(tool.explainProvenance(GID, "  ").property()).isNull();
  }

  @Test
  void emptyResultIsAssetNotFoundWithoutGid() {
    when(queries.execute(eq("explain_provenance"), any(), any()))
        .thenReturn(new QueryResult("explain_provenance", List.of(), false, 0, Duration.ZERO));

    assertThatThrownBy(() -> tool.explainProvenance(GID, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asset not found")
        .hasMessageNotContaining(GID);
  }
}
