package io.github.unlocker.archrag.graphquerycore.templates;

import java.util.Locale;
import java.util.Set;

/**
 * Превращает пользовательский ввод в безопасную строку для Lucene-запроса FULLTEXT-индекса.
 *
 * <p>Инвариант: результат не содержит операторов и спецсимволов Lucene и приведён к нижнему регистру,
 * поэтому {@code AND}/{@code OR}/{@code NOT} перестают быть операторами и ввод не ломает разбор запроса.
 */
public final class FulltextQuery {

  private static final Set<Character> SPECIAL =
      Set.of('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/');

  private FulltextQuery() {}

  /**
   * Экранирует спецсимволы Lucene и приводит ввод к нижнему регистру.
   *
   * @param input сырой ввод; может быть {@code null}
   * @return экранированная строка или {@code null}, если после очистки не осталось текста
   */
  public static String escape(String input) {
    if (input == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(input.length() + 8);
    for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
      if (SPECIAL.contains(c)) {
        out.append('\\');
      }
      out.append(c);
    }
    String result = out.toString().strip();
    // Строка только из пробелов и экранированных символов осмысленного запроса не даёт.
    boolean hasContent = result.chars().anyMatch(Character::isLetterOrDigit);
    return hasContent ? result : null;
  }
}
