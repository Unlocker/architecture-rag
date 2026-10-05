package io.github.unlocker.archrag.identityresolution;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Нормализация значений признаков. Чистые функции; пустой результат — {@link Optional#empty()}
 * (признака нет). Нечёткого сравнения нет: две записи совпадают, только если значения равны посимвольно.
 */
public final class FeatureNormalizer {

  private static final Pattern SCP_LIKE = Pattern.compile("^[^/@\\s]+@([^:/\\s]+):(.*)$");
  private static final Pattern SCHEME = Pattern.compile("^(?:https?|ssh|git)://", Pattern.CASE_INSENSITIVE);
  private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

  private FeatureNormalizer() {}

  /** trim, lower-case, без завершающих точек; FQDN целиком, без усечения до short name. */
  public static Optional<String> hostname(String raw) {
    String s = raw.trim().toLowerCase(Locale.ROOT);
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '.') {
      end--;
    }
    return nonEmpty(s.substring(0, end));
  }

  /** {@code host/path} в lower-case, без схемы, {@code user@}, {@code .git} и завершающего {@code /}. */
  public static Optional<String> repositoryUrl(String raw) {
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

  /** NFKC, lower-case, каждая серия символов не {@code \p{L}\p{N}} — один пробел, trim. */
  public static Optional<String> name(String raw) {
    String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    return nonEmpty(NON_ALNUM.matcher(s).replaceAll(" ").trim());
  }

  private static Optional<String> nonEmpty(String s) {
    return s.isEmpty() ? Optional.empty() : Optional.of(s);
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
