package io.github.unlocker.archrag.adminconsole.pg;

import java.util.List;

/**
 * Страница списка.
 *
 * @param items записи страницы
 * @param page номер страницы с нуля
 * @param size запрошенный размер страницы
 * @param total всего записей под фильтром
 */
public record Page<T>(List<T> items, int page, int size, long total) {}
