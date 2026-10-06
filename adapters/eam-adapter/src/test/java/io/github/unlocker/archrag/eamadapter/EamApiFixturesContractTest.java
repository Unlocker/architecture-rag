package io.github.unlocker.archrag.eamadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.sourcestubs.EamApiFormat;
import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.EamApiStub;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Контракт fixtures {@code src/test/resources/eam-api} по {@code docs/eam-api.yaml}: структура {@code ArchObject},
 * пагинация и шесть сценариев из {@code docs/06-eam-integration.md}. Адаптера под реальный формат ещё нет, поэтому тест
 * проверяет сами данные тем, как их будет читать адаптер: голый массив, {@code ver}, ссылки в {@code attrs}. Fixtures
 * собраны по спеке, а не записаны с реального EAM (допущения помечены в {@code eam-api/README.md}).
 */
class EamApiFixturesContractTest {

  private static final Path SPEC = Path.of("../../docs/eam-api.yaml");

  private static List<Map<String, Object>> load(String name) {
    try (var in = EamApiFixturesContractTest.class.getResourceAsStream("/eam-api/" + name)) {
      assertThat(in).as("fixture " + name).isNotNull();
      return EamApiFormat.parseArchObjects(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<Long> ids(List<Map<String, Object>> page) {
    return page.stream().map(EamApiFormat::id).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Map<Long, Map<String, Object>> byId(List<Map<String, Object>> page) {
    return page.stream().collect(Collectors.toMap(EamApiFormat::id, o -> o));
  }

  @SuppressWarnings("unchecked")
  private static List<Long> refs(Map<String, Object> object, String field) {
    return (List<Long>) EamApiFormat.attrs(object).getOrDefault(field, List.of());
  }

  /** Читает страницы подряд как адаптер: до первой страницы короче {@code pageSize}. */
  private static List<Map<String, Object>> readAll(int pageSize, String... pages) {
    List<Map<String, Object>> all = new ArrayList<>();
    for (String page : pages) {
      List<Map<String, Object>> objects = load(page);
      assertThat(objects).hasSizeLessThanOrEqualTo(pageSize);
      all.addAll(objects);
      if (EamApiFormat.isLastPage(objects, pageSize)) {
        return all;
      }
    }
    throw new AssertionError("no short page: the last page must be short or empty");
  }

  @Test
  void specDeclaresEndpointsAndArchObjectUsedByFixtures() throws IOException {
    String spec = Files.readString(SPEC);
    assertThat(spec).contains("title: EAM Tool API", "version: '0.4'", "  /api/platforms/:", "  /api/itsystems/:",
        "  /api/itproducts/:", "  /api/types/{id}/options/:", "name: page_size", "name: sort", "tokenAuth",
        "    ArchObject:");
    int archObject = spec.indexOf("    ArchObject:");
    String schema = spec.substring(archObject, spec.indexOf("    Buffer:", archObject));
    assertThat(schema).contains("id:", "ver:", "datetime:", "attrs:", "- attrs", "- id", "- ver");
  }

  @Test
  void everyFixtureIsBareArrayOfArchObjects() throws IOException {
    try (var files = Files.walk(Path.of("src/test/resources/eam-api"))) {
      List<Path> jsons = files.filter(p -> p.toString().endsWith(".json")).toList();
      assertThat(jsons).hasSizeGreaterThan(15);
      for (Path p : jsons) {
        if (p.getParent().getFileName().toString().equals("options")) {
          continue;
        }
        EamApiFormat.parseArchObjects(Files.readString(p));
      }
    }
  }

  @Test
  void typeMetadataDescribesReferenceFieldsAndSystemFields() {
    assertThat(load("types.json")).extracting(o -> EamApiFormat.attrs(o).get("typeurl"))
        .containsExactlyInAnyOrder("platforms", "itsystems", "itproducts");
    assertThat(fieldsOf("options/itsystems-fields.json")).contains("\"solution\"", "\"itproducts\"", "\"multiple\": true");
    assertThat(fieldsOf("options/itproducts-fields.json")).contains("\"platform\"", "\"platforms\"");
    assertThat(fieldsOf("options/itsystems-system.json")).contains("eam_id", "eam_name", "eam_permalink");
  }

  private static String fieldsOf(String name) {
    try (var in = EamApiFixturesContractTest.class.getResourceAsStream("/eam-api/" + name)) {
      var parsed = (Map<?, ?>) EamApiFormat.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      assertThat(parsed.get("options")).isInstanceOf(List.class);
      return new String(EamApiFixturesContractTest.class.getResourceAsStream("/eam-api/" + name).readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void paginationEndsOnPartialLastPage() {
    var all = readAll(2, "pagination-partial-last/platforms-page1.json", "pagination-partial-last/platforms-page2.json");
    assertThat(ids(all)).containsExactly(3101L, 3102L, 3103L);
    assertThat(load("pagination-partial-last/platforms-page2.json")).hasSizeLessThan(2);
  }

  @Test
  void paginationEndsOnEmptyLastPage() {
    var all = readAll(2, "pagination-empty-last/itproducts-page1.json", "pagination-empty-last/itproducts-page2.json",
        "pagination-empty-last/itproducts-page3.json");
    assertThat(ids(all)).containsExactly(7201L, 7202L, 7203L, 7204L);
    assertThat(load("pagination-empty-last/itproducts-page3.json")).isEmpty();
  }

  @Test
  void paginationWithoutShortPageIsNotTreatedAsFinished() {
    assertThatThrownBy(() -> readAll(2, "pagination-empty-last/itproducts-page1.json",
        "pagination-empty-last/itproducts-page2.json")).isInstanceOf(AssertionError.class);
  }

  @Test
  void repeatedVerWithSameAttrsIsNoOp() {
    var first = load("same-ver/itsystems-sync1.json").get(0);
    var second = load("same-ver/itsystems-sync2.json").get(0);
    assertThat(EamApiFormat.id(second)).isEqualTo(EamApiFormat.id(first));
    assertThat(EamApiFormat.ver(second)).isEqualTo(EamApiFormat.ver(first));
    assertThat(EamApiFormat.attrs(second)).isEqualTo(EamApiFormat.attrs(first));
    assertThat(second.get("datetime")).isEqualTo(first.get("datetime"));
  }

  @Test
  void changedReferenceBumpsVerAndMovesLink() {
    var before = load("ref-change/itproducts-sync1.json").get(0);
    var after = load("ref-change/itproducts-sync2.json").get(0);
    assertThat(EamApiFormat.id(after)).isEqualTo(EamApiFormat.id(before));
    assertThat(EamApiFormat.ver(after)).isGreaterThan(EamApiFormat.ver(before));
    Set<Long> closed = new LinkedHashSet<>(refs(before, "platform"));
    closed.removeAll(refs(after, "platform"));
    Set<Long> opened = new LinkedHashSet<>(refs(after, "platform"));
    opened.removeAll(refs(before, "platform"));
    assertThat(closed).containsExactly(3102L);
    assertThat(opened).containsExactly(3103L);
  }

  @Test
  void referenceToNotYetLoadedObjectIsDetectable() {
    var systems = load("deferred-reference/itsystems.json");
    Set<Long> knownSolutions = ids(load("deferred-reference/itproducts.json"));
    List<Long> unresolved = systems.stream().flatMap(s -> refs(s, "solution").stream())
        .filter(id -> !knownSolutions.contains(id)).toList();
    assertThat(unresolved).containsExactly(7299L);
  }

  @Test
  void objectMissingFromLaterSnapshotIsTombstoneCandidate() {
    Set<Long> before = ids(load("tombstone/platforms-snapshot1.json"));
    Set<Long> after = ids(load("tombstone/platforms-snapshot2.json"));
    Set<Long> missing = new LinkedHashSet<>(before);
    missing.removeAll(after);
    assertThat(missing).containsExactly(3102L);
    assertThat(after).isSubsetOf(before);
  }

  @Test
  void sameNameDifferentIdsAreDistinctObjects() {
    var systems = load("same-name/itsystems.json");
    assertThat(systems).hasSize(2);
    assertThat(systems).extracting(o -> EamApiFormat.attrs(o).get("name")).containsOnly("Payments Gateway");
    assertThat(ids(systems)).hasSize(2);
    assertThat(systems).extracting(o -> EamApiFormat.attrs(o).get("eam_id")).doesNotHaveDuplicates();
  }

  @Test
  void baselineFixturesMatchDemoStubSeed() throws Exception {
    try (var stub = new EamApiStub(EamApiSeed.seeded(Clock.systemUTC()), "t"); var http = HttpClient.newHttpClient()) {
      for (String type : List.of("platforms", "itproducts", "itsystems")) {
        var request = HttpRequest.newBuilder(URI.create(stub.baseUri() + "/api/" + type + "/"))
            .header("Authorization", "Token t").GET().build();
        var served = EamApiFormat.parseArchObjects(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        var fixture = load("baseline/" + type + ".json");
        assertThat(byId(served).keySet()).as(type).isEqualTo(byId(fixture).keySet());
        for (var o : fixture) {
          assertThat(EamApiFormat.attrs(byId(served).get(EamApiFormat.id(o)))).as(type).isEqualTo(EamApiFormat.attrs(o));
        }
      }
    }
  }
}
