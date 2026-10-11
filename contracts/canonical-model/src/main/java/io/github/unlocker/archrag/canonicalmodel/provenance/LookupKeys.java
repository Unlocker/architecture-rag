package io.github.unlocker.archrag.canonicalmodel.provenance;

/**
 * Псевдонимы-ключи для ссылок, которые источник задаёт не ключом записи. Записи с таким ключом не существует:
 * он нужен, чтобы отложить связь, а проектор достраивает её, когда появляется запись с нужным свойством.
 * Единственное место, знающее формат псевдонима.
 */
public final class LookupKeys {

    /** {@code sourceType} псевдонима репозитория. */
    public static final String REPOSITORY_URL = "REPOSITORY_URL";

    private LookupKeys() {
    }

    /** Псевдоним репозитория SCM по каноническому URL ({@code GitUrls.normalize}). */
    public static SourceKey repositoryUrl(String normalizedUrl) {
        return new SourceKey(SourceSystemCode.SCM, REPOSITORY_URL, normalizedUrl);
    }
}
