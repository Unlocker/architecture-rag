package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Снятие признаков идентичности с узла, их нормализация и score пары.
 *
 * <p>Чистые функции без хранилища. Сравнение только на равенство нормализованных значений: нечёткого
 * совпадения нет. {@code Environment}, {@code Deployment} и прочие метки признаков не дают.
 */
public final class IdentityFeatures {

  private static final Pattern SCP_LIKE = Pattern.compile("^[^/@\\s]+@([^:/\\s]+):(.*)$");
  private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
  private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

  private IdentityFeatures() {}

  /**
   * Признаки узла.
   *
   * @param owner {@code gid} команды-владельца из того же события, если известен; учитывается только у
   *     узлов, у которых есть собственные признаки
   */
  public static Set<Feature> of(NodeData data, Optional<UUID> owner) {
    Set<Feature> features = new LinkedHashSet<>();
    switch (data) {
      case ComputeInstance c -> add(features, FeatureType.HOSTNAME, hostname(c.hostname()));
      case Repository r -> add(features, FeatureType.REPOSITORY_URL, repositoryUrl(r.url()));
      case ITSystem s -> add(features, FeatureType.NAME, name(s.name()));
      case Service s -> add(features, FeatureType.NAME, name(s.name()));
      case Team t -> add(features, FeatureType.NAME, name(t.name()));
      default -> {}
    }
    if (!features.isEmpty()) {
      owner.ifPresent(gid -> features.add(new Feature(FeatureType.OWNER, gid.toString())));
    }
    return features;
  }

  /** trim, lower-case, без завершающей точки; пустая строка, если значения не осталось. */
  public static String hostname(String raw) {
    String s = raw.trim().toLowerCase(Locale.ROOT);
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '.') {
      end--;
    }
    return s.substring(0, end);
  }

  /** {@code host/path}: host в lower-case, без схемы, userinfo, {@code .git} и завершающего {@code /}. */
  public static String repositoryUrl(String raw) {
    String s = raw.trim();
    String host;
    String path;
    Matcher scp = s.contains("://") ? null : SCP_LIKE.matcher(s);
    if (scp != null && scp.matches()) {
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
      return "";
    }
    return path.isEmpty() ? host : host + "/" + path;
  }

  /** trim, lower-case, серии не-буквенно-цифровых символов — один пробел. */
  public static String name(String raw) {
    return NON_ALNUM.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
  }

  /** Score пары {@code 1 - Π(1 - wᵢ)} по совпавшим типам, шкала 3. */
  public static BigDecimal score(Collection<FeatureType> matched) {
    BigDecimal miss = BigDecimal.ONE;
    EnumSet<FeatureType> distinct = EnumSet.noneOf(FeatureType.class);
    distinct.addAll(matched);
    for (FeatureType type : distinct) {
      miss = miss.multiply(BigDecimal.ONE.subtract(type.weight()));
    }
    return BigDecimal.ONE.subtract(miss).setScale(3, RoundingMode.HALF_UP);
  }

  private static void add(Set<Feature> features, FeatureType type, String value) {
    if (!value.isEmpty()) {
      features.add(new Feature(type, value));
    }
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
