package io.github.unlocker.archrag.adminconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adminconsole.pg.SyncSources;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.ConsoleTemplates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code allowlists.json} админ-UI совпадает с allowlist'ами бэкенда, которые отвергают неизвестное значение (400). */
class AdminUiAllowlistsContractTest {

  private static final Path JSON = Path.of("../admin-ui/src/api/allowlists.json");

  private final JsonNode allowlists = read();

  @Test
  void assetTypesEqualSearchableTypes() {
    assertThat(values("ASSET_TYPES")).containsExactlyElementsOf(AssetTemplates.SEARCHABLE_TYPES);
  }

  @Test
  void relationTypesEqualNeighborhoodRelationTypes() {
    assertThat(values("RELATION_TYPES")).containsExactlyElementsOf(ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES);
  }

  @Test
  void sourcesEqualSyncSourcesCodes() {
    assertThat(values("SOURCES")).containsExactlyElementsOf(SyncSources.CODES);
  }

  @Test
  void eventStatusesEqualSyncSourcesStatuses() {
    assertThat(values("EVENT_STATUSES")).containsExactlyElementsOf(SyncSources.STATUSES);
  }

  @Test
  void jsonHasNoUnexpectedKeys() {
    assertThat(allowlists.propertyNames())
        .containsExactlyInAnyOrder("ASSET_TYPES", "RELATION_TYPES", "SOURCES", "EVENT_STATUSES");
  }

  private List<String> values(String key) {
    return allowlists.get(key).valueStream().map(JsonNode::asString).toList();
  }

  private static JsonNode read() {
    try {
      return JsonMapper.builder().build().readTree(Files.readString(JSON));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + JSON.toAbsolutePath(), e);
    }
  }
}
