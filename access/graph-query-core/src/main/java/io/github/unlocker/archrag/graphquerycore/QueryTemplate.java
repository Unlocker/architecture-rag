package io.github.unlocker.archrag.graphquerycore;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Именованный параметризованный шаблон Cypher.
 *
 * <p>Инварианты, проверяемые в конструкторе: текст заканчивается на {@code LIMIT $limit}; множество
 * {@code $param} в тексте совпадает с {@code parameters} (плюс зарезервированный {@code $limit});
 * единственный допустимый плейсхолдер — {@code {maxDepth}}, и только как верхняя граница
 * variable-length пути; любой variable-length путь имеет верхнюю границу.
 *
 * @param id уникальный ID шаблона
 * @param cypher текст запроса без подстановки строк
 * @param parameters имена параметров, которые обязан передать вызывающий (без {@code limit})
 * @param defaults бюджет по умолчанию; {@code null} — потолки конфигурации
 */
public record QueryTemplate(
    String id, String cypher, Set<String> parameters, ResultBudget defaults) {

  /** Имя параметра лимита строк: его задаёт исполнитель, вызывающий передать его не может. */
  public static final String LIMIT_PARAMETER = "limit";

  /** Единственный разрешённый плейсхолдер текста шаблона. */
  public static final String MAX_DEPTH_PLACEHOLDER = "{maxDepth}";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*[A-Za-z_][A-Za-z0-9_]*\\s*}");
  private static final Pattern PARAM = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");
  private static final Pattern ENDS_WITH_LIMIT =
      Pattern.compile("(?is).*\\bLIMIT\\s+\\$limit\\s*;?\\s*");
  // Квантификатор «*» внутри квадратных скобок связи: [r:TYPE*..], [*1..3].
  private static final Pattern VAR_LENGTH =
      Pattern.compile("\\[\\s*[A-Za-z_0-9]*\\s*(?::[^\\]*]*)?\\*([^\\]]*)]");
  private static final Pattern BOUNDED = Pattern.compile("\\s*(?:\\d*\\s*\\.\\.\\s*)?(\\d+|\\{maxDepth})\\s*");

  public QueryTemplate {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Template id must not be blank");
    }
    if (cypher == null || cypher.isBlank()) {
      throw new IllegalArgumentException("Template '" + id + "': cypher must not be blank");
    }
    parameters = Set.copyOf(parameters);
    if (parameters.contains(LIMIT_PARAMETER)) {
      throw new IllegalArgumentException(
          "Template '" + id + "': parameter 'limit' is reserved for the executor");
    }
    validate(id, cypher, parameters);
  }

  private static void validate(String id, String cypher, Set<String> parameters) {
    Matcher placeholders = PLACEHOLDER.matcher(cypher);
    int depthPlaceholders = 0;
    while (placeholders.find()) {
      if (!placeholders.group().replaceAll("\\s", "").equals(MAX_DEPTH_PLACEHOLDER)) {
        throw new IllegalArgumentException(
            "Template '" + id + "': placeholder " + placeholders.group() + " is not allowed");
      }
      depthPlaceholders++;
    }
    if (depthPlaceholders > 1) {
      throw new IllegalArgumentException(
          "Template '" + id + "': only one " + MAX_DEPTH_PLACEHOLDER + " is allowed");
    }
    if (!ENDS_WITH_LIMIT.matcher(cypher).matches()) {
      throw new IllegalArgumentException("Template '" + id + "': must end with 'LIMIT $limit'");
    }
    Set<String> used = new HashSet<>();
    Matcher params = PARAM.matcher(cypher);
    while (params.find()) {
      used.add(params.group(1));
    }
    used.remove(LIMIT_PARAMETER);
    if (!used.equals(parameters)) {
      throw new IllegalArgumentException(
          "Template '" + id + "': declared parameters " + parameters + " differ from used " + used);
    }
    int depthUses = 0;
    Matcher varLength = VAR_LENGTH.matcher(cypher);
    while (varLength.find()) {
      Matcher bound = BOUNDED.matcher(varLength.group(1));
      if (!bound.matches()) {
        throw new IllegalArgumentException(
            "Template '" + id + "': variable-length path must have an upper bound: "
                + varLength.group());
      }
      if (bound.group(1).equals(MAX_DEPTH_PLACEHOLDER)) {
        depthUses++;
      }
    }
    if (depthUses != depthPlaceholders) {
      throw new IllegalArgumentException(
          "Template '" + id + "': " + MAX_DEPTH_PLACEHOLDER
              + " is allowed only as the upper bound of a variable-length path");
    }
  }

  /** Есть ли в шаблоне плейсхолдер глубины. */
  public boolean hasDepthPlaceholder() {
    return cypher.contains(MAX_DEPTH_PLACEHOLDER);
  }

  /**
   * Подставляет глубину в плейсхолдер.
   *
   * <p>Граница variable-length пути в Cypher не параметризуется, поэтому это единственное место
   * подстановки в текст; в запрос попадает только проверенный {@code int}.
   *
   * @throws IllegalArgumentException если {@code depth} вне диапазона {@code 1..ceiling}
   */
  public String render(int depth, int ceiling) {
    if (depth < 1 || depth > ceiling) {
      throw new IllegalArgumentException("maxDepth " + depth + " is outside 1.." + ceiling);
    }
    return cypher.replaceAll("\\{\\s*maxDepth\\s*}", Integer.toString(depth));
  }
}
