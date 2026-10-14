package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Запускает полный snapshot источника в adapter-сервисе через его {@code POST /control/snapshot}. Своего прохода
 * по источнику здесь нет: удаления делает только {@code Reconciler} по маркеру {@code snapshot-complete}.
 */
@Component
public class AdapterControlClient {

  /** Snapshot синхронный, поэтому ожидание длинное. */
  static final Duration TIMEOUT = Duration.ofMinutes(10);

  private final AdaptersProperties properties;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public AdapterControlClient(AdaptersProperties properties) {
    this.properties = properties;
  }

  /** Ответ адаптера. */
  public record Response(int status, String body) {}

  /** Короткий код источника ({@code eam}, ...) для {@code urn:corp:eam} или самого кода. */
  public static String code(String source) {
    return SourceSystemCode.fromUrn(source).map(SourceSystemCode::code).orElse(source);
  }

  /** {@code true}, если для источника настроен адрес адаптера. */
  public boolean knows(String source) {
    return controlUrl(source).isPresent();
  }

  private Optional<URI> controlUrl(String source) {
    var adapter = properties.adapters().get(code(source));
    if (adapter == null || adapter.controlUrl() == null || adapter.controlUrl().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(URI.create(adapter.controlUrl()));
  }

  /**
   * Запускает snapshot. Bearer-токен администратора, пришедший в {@code AdminController}, пробрасывается адаптеру: он
   * сам проверяет подпись, срок, audience и роль.
   *
   * @throws AdapterUnavailableException если адаптер недоступен или ответил не {@code 202}/{@code 409}
   * @throws IllegalArgumentException если адрес источника не настроен
   */
  public Response snapshot(String source, String bearerToken) {
    URI uri = controlUrl(source).orElseThrow(() -> new IllegalArgumentException("unknown source"));
    try {
      HttpResponse<String> r = http.send(
          HttpRequest.newBuilder(uri).timeout(TIMEOUT)
              .header("Authorization", "Bearer " + bearerToken).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 202 && r.statusCode() != 409) {
        // Тело и токен не логируем и не кладём в исключение.
        throw new AdapterUnavailableException();
      }
      return new Response(r.statusCode(), r.body());
    } catch (IOException e) {
      throw new AdapterUnavailableException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AdapterUnavailableException();
    }
  }
}
