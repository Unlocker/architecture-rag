package io.github.unlocker.archrag.sourcestubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EamApiStubTest {

  private static final String TOKEN = "test-token";
  private final HttpClient http = HttpClient.newHttpClient();
  private EamApiStore store;
  private EamApiStub stub;

  @BeforeEach
  void start() {
    store = EamApiSeed.seeded(Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));
    stub = new EamApiStub(store, TOKEN);
  }

  @AfterEach
  void stop() {
    stub.close();
    http.close();
  }

  private HttpResponse<String> call(String method, String path, String authorization) throws Exception {
    var b = HttpRequest.newBuilder(URI.create(stub.baseUri() + path))
        .method(method, HttpRequest.BodyPublishers.noBody());
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return call("GET", path, "Token " + TOKEN);
  }

  @Test
  void listReturnsBareArrayOfArchObjectsForAllThreeTypes() throws Exception {
    for (String type : List.of(EamApiSeed.PLATFORM_TYPE, EamApiSeed.SOLUTION_TYPE, EamApiSeed.IT_SYSTEM_TYPE)) {
      var r = get("/api/" + type + "/");
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(EamApiFormat.parseArchObjects(r.body())).as(type).hasSize(2);
    }
  }

  @Test
  void seedSystemsCarryEamIdMatchingScmSystemCode() throws Exception {
    var systems = EamApiFormat.parseArchObjects(get("/api/itsystems/").body());
    assertThat(systems).extracting(o -> EamApiFormat.attrs(o).get("eam_id")).containsExactly("EAM-1042", "EAM-2001");
    assertThat(systems).extracting(EamApiFormat::id).containsExactly(5101L, 5102L);
  }

  @Test
  void paginationEndsWithShortThenEmptyPage() throws Exception {
    var p1 = EamApiFormat.parseArchObjects(get("/api/platforms/?page=1&page_size=2").body());
    var p2 = EamApiFormat.parseArchObjects(get("/api/platforms/?page=2&page_size=2").body());
    assertThat(p1).hasSize(2);
    assertThat(EamApiFormat.isLastPage(p1, 2)).isFalse();
    assertThat(p2).isEmpty();
    var one = EamApiFormat.parseArchObjects(get("/api/platforms/?page=1&page_size=1").body());
    assertThat(one).hasSize(1);
  }

  @Test
  void sortByMtimeDescendingPutsLatestChangeFirst() throws Exception {
    var ticking = new TickingClock();
    store = EamApiSeed.seeded(ticking);
    stub.close();
    stub = new EamApiStub(store, TOKEN);
    ticking.advance();
    store.upsert(EamApiSeed.PLATFORM_TYPE, 3101, Map.of("name", "Kubernetes Platform v2"));
    var byMtime = EamApiFormat.parseArchObjects(get("/api/platforms/?sort=-mtime").body());
    assertThat(byMtime).extracting(EamApiFormat::id).containsExactly(3101L, 3102L);
    var byId = EamApiFormat.parseArchObjects(get("/api/platforms/?sort=-id").body());
    assertThat(byId).extracting(EamApiFormat::id).containsExactly(3102L, 3101L);
  }

  @Test
  void fieldsParameterRestrictsAttrs() throws Exception {
    var o = EamApiFormat.parseArchObjects(get("/api/itsystems/?fields=name").body()).get(0);
    assertThat(EamApiFormat.attrs(o)).containsOnlyKeys("name");
  }

  @Test
  void pageSizeIsClampedToMaximum() throws Exception {
    assertThat(get("/api/platforms/?page_size=100000").statusCode()).isEqualTo(200);
  }

  @Test
  void verGrowsOnChangeAndStaysOnRepeat() throws Exception {
    Map<String, Object> attrs = Map.of("name", "X");
    store.upsert("platforms", 9, attrs);
    store.upsert("platforms", 9, attrs);
    assertThat(EamApiFormat.ver(EamApiFormat.checkArchObject(parseOne("/api/platforms/9/")))).isEqualTo(1L);
    store.upsert("platforms", 9, Map.of("name", "Y"));
    assertThat(EamApiFormat.ver(EamApiFormat.checkArchObject(parseOne("/api/platforms/9/")))).isEqualTo(2L);
  }

  private Object parseOne(String path) throws Exception {
    return EamApiFormat.parse(get(path).body());
  }

  @Test
  void archivedObjectDisappearsFromListAndRetrieve() throws Exception {
    store.archive("platforms", 3102);
    assertThat(EamApiFormat.parseArchObjects(get("/api/platforms/").body())).hasSize(1);
    assertThat(get("/api/platforms/3102/").statusCode()).isEqualTo(404);
  }

  @Test
  void retrieveByIdAndUnknownIdOrType() throws Exception {
    assertThat(get("/api/itsystems/5101/").statusCode()).isEqualTo(200);
    assertThat(get("/api/itsystems/1/").statusCode()).isEqualTo(404);
    assertThat(get("/api/nosuchtype/").statusCode()).isEqualTo(404);
    assertThat(get("/api/itsystems/abc/").statusCode()).isEqualTo(400);
  }

  @Test
  void typeMetadataIsServed() throws Exception {
    var types = EamApiFormat.parseArchObjects(get("/api/types/").body());
    assertThat(types).hasSize(3);
    assertThat(get("/api/types/12/").statusCode()).isEqualTo(200);
    var fields = (Map<?, ?>) parseOne("/api/types/itsystems/options/?kind=fields");
    assertThat((List<?>) fields.get("options")).isNotEmpty();
    var system = (Map<?, ?>) parseOne("/api/types/12/options/?kind=system");
    assertThat(system.get("options").toString()).contains("eam_id");
    assertThat(get("/api/types/12/options/?kind=groups").statusCode()).isEqualTo(400);
  }

  @Test
  void requestsWithoutValidTokenAreRejected() throws Exception {
    assertThat(call("GET", "/api/platforms/", null).statusCode()).isEqualTo(401);
    assertThat(call("GET", "/api/platforms/", "Token wrong").statusCode()).isEqualTo(401);
    assertThat(call("GET", "/api/platforms/", "Bearer " + TOKEN).statusCode()).isEqualTo(401);
  }

  @Test
  void mutatingMethodsAreNotAllowed() throws Exception {
    for (String method : List.of("POST", "PATCH", "DELETE")) {
      assertThat(call(method, "/api/platforms/", "Token " + TOKEN).statusCode()).isEqualTo(405);
    }
  }

  @Test
  void blankTokenIsRejected() {
    assertThatThrownBy(() -> new EamApiStub(store, " ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parserRejectsMalformedArchObjects() {
    assertThatThrownBy(() -> EamApiFormat.parseArchObjects("{\"items\":[]}")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EamApiFormat.parseArchObjects("[{\"id\":\"1\",\"ver\":1,\"attrs\":{}}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EamApiFormat.parseArchObjects("[{\"id\":1,\"attrs\":{}}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EamApiFormat.parseArchObjects("[{\"id\":1,\"ver\":1,\"datetime\":\"x\",\"attrs\":{}}]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateFieldsAreAccepted() throws Exception {
    var o = EamApiFormat.parseArchObjects(get("/api/itsystems/?fields=name,name").body()).get(0);
    assertThat(EamApiFormat.attrs(o)).containsOnlyKeys("name");
  }

  @Test
  void sortByUnsupportedKeyIsRejected() throws Exception {
    assertThat(get("/api/platforms/?sort=name").statusCode()).isEqualTo(400);
  }

  @Test
  void truncatedJsonFailsWithIllegalArgument() {
    assertThatThrownBy(() -> EamApiFormat.parse("[{\"id\":1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EamApiFormat.parse("[")).isInstanceOf(IllegalArgumentException.class);
  }

  private static final class TickingClock extends Clock {
    private Instant now = Instant.parse("2026-10-06T12:00:00Z");

    void advance() {
      now = now.plusSeconds(60);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
