package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Совместимость значений {@code identity_feature.value} (kind {@code REPOSITORY_URL}): старая
 * реализация {@code repositoryUrl} против новой, делегирующей в {@code GitUrls.matchKey}.
 */
class RepositoryUrlCompatibilityTest {

  private static final Pattern SCP_LIKE = Pattern.compile("^[^/@\\s]+@([^:/\\s]+):(.*)$");
  private static final Pattern SCHEME =
      Pattern.compile("^(?:https?|ssh|git)://", Pattern.CASE_INSENSITIVE);

  /** Набор URL: ключ — вход, значение — ожидаемый результат новой реализации. */
  private static final Map<String, Optional<String>> SAME =
      Map.ofEntries(
          Map.entry("https://github.com/org/repo", Optional.of("github.com/org/repo")),
          Map.entry("http://github.com/org/repo", Optional.of("github.com/org/repo")),
          Map.entry("https://GitHub.com/Org/Repo.git/", Optional.of("github.com/org/repo")),
          Map.entry("ssh://git@github.com/org/repo.git", Optional.of("github.com/org/repo")),
          Map.entry("git://github.com/org/repo.git", Optional.of("github.com/org/repo")),
          Map.entry("git@github.com:Org/Repo.git", Optional.of("github.com/org/repo")),
          Map.entry("https://user@git.example.org/a/b", Optional.of("git.example.org/a/b")),
          Map.entry("https://git.example.org:8443/a/b", Optional.of("git.example.org:8443/a/b")),
          Map.entry("http://git.example.org:8080/A/b.git", Optional.of("git.example.org:8080/a/b")),
          Map.entry("https://git.example.org/a/b/", Optional.of("git.example.org/a/b")),
          Map.entry("  https://git.example.org/a/b  ", Optional.of("git.example.org/a/b")),
          Map.entry("   ", Optional.empty()));

  @Test
  void newImplementationMatchesOldOnCommonForms() {
    SAME.forEach(
        (raw, expected) -> {
          assertThat(FeatureNormalizer.repositoryUrl(raw)).as("new " + raw).isEqualTo(expected);
          assertThat(legacy(raw)).as("old " + raw).isEqualTo(expected);
        });
  }

  /** Расхождения: форма, где старая реализация даёт другое значение или значение вместо «нет признака». */
  @Test
  void documentedDivergences() {
    // порт ssh: старая оставляла его в host, новая отбрасывает (как GitUrls.normalize)
    assertThat(legacy("ssh://git@git.example.org:2222/a/b")).contains("git.example.org:2222/a/b");
    assertThat(FeatureNormalizer.repositoryUrl("ssh://git@git.example.org:2222/a/b"))
        .contains("git.example.org/a/b");
    // URL без пути: старая возвращала host, новая — признака нет
    assertThat(legacy("https://git.example.org")).contains("git.example.org");
    assertThat(FeatureNormalizer.repositoryUrl("https://git.example.org")).isEmpty();
    // scp без user@: старая трактовала как схемный URL, новая распознаёт scp
    assertThat(legacy("git.example.org:a/b")).isNotEqualTo(Optional.of("git.example.org/a/b"));
    assertThat(FeatureNormalizer.repositoryUrl("git.example.org:a/b")).contains("git.example.org/a/b");
    // нераспознанная схема: старая принимала любое «host/path», новая — нет
    assertThat(legacy("host.example.org/a/b")).contains("host.example.org/a/b");
    assertThat(FeatureNormalizer.repositoryUrl("host.example.org/a/b")).isEmpty();
  }

  /** Копия реализации до объединения парсеров. */
  private static Optional<String> legacy(String raw) {
    String s = raw.trim();
    String host;
    String path;
    Matcher scp = SCP_LIKE.matcher(s);
    if (!s.contains("://") && scp.matches()) {
      host = scp.group(1);
      path = scp.group(2);
    } else {
      s = SCHEME.matcher(s).replaceFirst("");
      int slash = s.indexOf('/');
      String authority = slash < 0 ? s : s.substring(0, slash);
      path = slash < 0 ? "" : s.substring(slash + 1);
      host = authority.substring(authority.lastIndexOf('@') + 1);
    }
    path = stripSlashes(path);
    if (path.endsWith(".git")) {
      path = stripSlashes(path.substring(0, path.length() - ".git".length()));
    }
    host = host.trim().toLowerCase(Locale.ROOT);
    if (host.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(path.isEmpty() ? host : host + "/" + path.toLowerCase(Locale.ROOT));
  }

  private static String stripSlashes(String path) {
    int start = 0;
    int end = path.length();
    while (start < end && path.charAt(start) == '/') {
      start++;
    }
    while (end > start && path.charAt(end - 1) == '/') {
      end--;
    }
    return path.substring(start, end);
  }
}
