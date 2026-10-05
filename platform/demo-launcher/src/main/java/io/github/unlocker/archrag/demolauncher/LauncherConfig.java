package io.github.unlocker.archrag.demolauncher;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Разбор конфигурации лаунчера из переменных окружения. Обязательное значение без умолчания, которое не задано или
 * пусто, даёт {@link IllegalArgumentException} со списком <em>имён</em> переменных (значения в сообщения не попадают).
 */
public final class LauncherConfig {

  static final String PG_URL = "ARCHRAG_PG_URL";
  static final String PG_USER = "ARCHRAG_PG_USER";
  static final String PG_PASSWORD = "ARCHRAG_PG_PASSWORD";
  static final String S3_ENDPOINT = "ARCHRAG_S3_ENDPOINT";
  static final String S3_ACCESS_KEY = "ARCHRAG_S3_ACCESS_KEY";
  static final String S3_SECRET_KEY = "ARCHRAG_S3_SECRET_KEY";
  static final String S3_BUCKET = "ARCHRAG_S3_BUCKET";
  static final String SOURCE_URL = "ARCHRAG_SOURCE_URL";
  static final String WEBHOOK_SECRET = "ARCHRAG_WEBHOOK_SECRET";
  static final String WEBHOOK_PORT = "ARCHRAG_WEBHOOK_PORT";
  static final String HEALTH_PORT = "ARCHRAG_HEALTH_PORT";
  static final String POLL_INTERVAL_SECONDS = "ARCHRAG_POLL_INTERVAL_SECONDS";
  static final String RECONCILE_INTERVAL_SECONDS = "ARCHRAG_RECONCILE_INTERVAL_SECONDS";
  static final String STUB_PORT = "ARCHRAG_STUB_PORT";

  private LauncherConfig() {}

  /**
   * Настройки адаптера.
   *
   * @param webhookPort порт webhook и {@code /control/snapshot}; внутри compose-сети, наружу публикуется только
   *     {@code /webhook} через proxy
   * @param healthPort порт {@code GET /health}
   */
  public record Adapter(
      SourceSystem system,
      String pgUrl,
      String pgUser,
      String pgPassword,
      URI s3Endpoint,
      String s3AccessKey,
      String s3SecretKey,
      String s3Bucket,
      URI sourceUrl,
      String webhookSecret,
      int webhookPort,
      int healthPort,
      Duration pollInterval,
      Duration reconcileInterval) {

    @Override
    public String toString() {
      return "Adapter[system=" + system + ", sourceUrl=" + sourceUrl + ", webhookPort=" + webhookPort
          + ", healthPort=" + healthPort + ", secrets=***]";
    }
  }

  /** Настройки заглушки источника: порт, на котором она слушает все интерфейсы контейнера. */
  public record Stub(SourceSystem system, int port) {}

  /** Разбирает источник из аргумента командной строки: {@code EAM}, {@code SCM}, {@code CMDB}, {@code DEPLOY_MAP}. */
  public static SourceSystem parseSystem(String arg) {
    try {
      return SourceSystem.valueOf(arg);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("unknown source system, expected one of " + List.of(SourceSystem.values()));
    }
  }

  /** Настройки адаптера из env. */
  public static Adapter adapter(SourceSystem system, Map<String, String> env) {
    List<String> missing = new ArrayList<>();
    String pgUrl = required(env, PG_URL, missing);
    String pgUser = required(env, PG_USER, missing);
    String pgPassword = required(env, PG_PASSWORD, missing);
    String s3Endpoint = required(env, S3_ENDPOINT, missing);
    String s3AccessKey = required(env, S3_ACCESS_KEY, missing);
    String s3SecretKey = required(env, S3_SECRET_KEY, missing);
    String s3Bucket = required(env, S3_BUCKET, missing);
    String sourceUrl = required(env, SOURCE_URL, missing);
    String secret = required(env, WEBHOOK_SECRET, missing);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("required environment variables are not set: " + missing);
    }
    return new Adapter(system, pgUrl, pgUser, pgPassword, URI.create(s3Endpoint), s3AccessKey, s3SecretKey, s3Bucket,
        URI.create(sourceUrl), secret, port(env, WEBHOOK_PORT, 8080), port(env, HEALTH_PORT, 8090),
        seconds(env, POLL_INTERVAL_SECONDS, 30), seconds(env, RECONCILE_INTERVAL_SECONDS, 6 * 3600));
  }

  /** Настройки заглушки из env. */
  public static Stub stub(SourceSystem system, Map<String, String> env) {
    return new Stub(system, port(env, STUB_PORT, 8080));
  }

  private static String required(Map<String, String> env, String name, List<String> missing) {
    String v = env.get(name);
    if (v == null || v.isBlank()) {
      missing.add(name);
      return null;
    }
    return v;
  }

  private static int port(Map<String, String> env, String name, int fallback) {
    String v = env.get(name);
    if (v == null || v.isBlank()) {
      return fallback;
    }
    try {
      int p = Integer.parseInt(v.trim());
      if (p < 1 || p > 65535) {
        throw new IllegalArgumentException(name + " must be in 1..65535");
      }
      return p;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be an integer");
    }
  }

  private static Duration seconds(Map<String, String> env, String name, long fallback) {
    String v = env.get(name);
    if (v == null || v.isBlank()) {
      return Duration.ofSeconds(fallback);
    }
    try {
      long s = Long.parseLong(v.trim());
      if (s < 1) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return Duration.ofSeconds(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be an integer number of seconds");
    }
  }
}
