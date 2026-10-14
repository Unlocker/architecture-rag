package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.GitUrls;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Нормализация значений признаков. Чистые функции; пустой результат — {@link Optional#empty()}
 * (признака нет). Нечёткого сравнения нет: две записи совпадают, только если значения равны посимвольно.
 */
public final class FeatureNormalizer {

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

  /**
   * Ключ сравнения репозитория {@code host[:port]/path} в lower-case ({@link GitUrls#matchKey}).
   * Нераспознанный URL — признака нет.
   */
  public static Optional<String> repositoryUrl(String raw) {
    try {
      return Optional.of(GitUrls.matchKey(raw));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** NFKC, lower-case, каждая серия символов не {@code \p{L}\p{N}} — один пробел, trim. */
  public static Optional<String> name(String raw) {
    String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    return nonEmpty(NON_ALNUM.matcher(s).replaceAll(" ").trim());
  }

  private static Optional<String> nonEmpty(String s) {
    return s.isEmpty() ? Optional.empty() : Optional.of(s);
  }
}
