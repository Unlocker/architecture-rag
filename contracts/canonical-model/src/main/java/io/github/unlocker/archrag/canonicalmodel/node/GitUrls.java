package io.github.unlocker.archrag.canonicalmodel.node;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Нормализация URL Git-репозитория к каноническому виду {@code <scheme>://<host>[:port]/<path>}.
 *
 * <p>Инварианты результата: схема и хост в нижнем регистре, путь сохраняет регистр, без учётных
 * данных, без суффикса {@code .git} и завершающего {@code /}. Схемы {@code ssh}, {@code git} и
 * scp-форма {@code git@host:org/repo} приводятся к {@code https}; {@code http} остаётся {@code
 * http}. Порт сохраняется только для {@code http(s)}.
 */
public final class GitUrls {

  /** scp-форма: {@code [user@]host:path}, путь не начинается с {@code /}. */
  private static final Pattern SCP = Pattern.compile("^(?:[^@/\\s]+@)?([^:/\\s]+):(?!//)(.+)$");

  private GitUrls() {}

  /**
   * Приводит URL репозитория к каноническому виду.
   *
   * @throws IllegalArgumentException если значение {@code null}, пустое или не распознано как URL
   *     Git-репозитория
   */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("git url is blank");
    }
    String value = raw.strip();
    String scheme;
    String host;
    int port = -1;
    String path;

    Matcher scp = SCP.matcher(value);
    if (!value.contains("://") && scp.matches()) {
      scheme = "https";
      host = scp.group(1);
      path = scp.group(2);
    } else {
      URI uri;
      try {
        uri = new URI(value);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("unrecognized git url", e);
      }
      String s = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      switch (s) {
        case "https", "ssh", "git" -> scheme = "https";
        case "http" -> scheme = "http";
        default -> throw new IllegalArgumentException("unsupported git url scheme");
      }
      host = uri.getHost();
      path = uri.getRawPath();
      if (s.equals("http") || s.equals("https")) {
        port = uri.getPort();
      }
    }
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("git url has no host");
    }
    path = trimPath(path);
    if (path.isEmpty()) {
      throw new IllegalArgumentException("git url has no repository path");
    }
    return scheme + "://" + host.toLowerCase(Locale.ROOT) + (port >= 0 ? ":" + port : "") + "/"
        + path;
  }

  private static String trimPath(String path) {
    String p = path == null ? "" : path;
    while (p.startsWith("/")) {
      p = p.substring(1);
    }
    while (p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (p.endsWith(".git")) {
      p = p.substring(0, p.length() - 4);
    }
    while (p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p;
  }
}
