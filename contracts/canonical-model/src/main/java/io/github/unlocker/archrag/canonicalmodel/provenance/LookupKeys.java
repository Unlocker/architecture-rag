package io.github.unlocker.archrag.canonicalmodel.provenance;

/**
 * Псевдонимы-ключи для ссылок, которые источник задаёт не ключом записи. Записи с таким ключом не существует:
 * он нужен, чтобы отложить связь, а проектор достраивает её, когда появляется запись с нужным свойством.
 * Единственное место, знающее формат псевдонима.
 */
public final class LookupKeys {

    /** {@code sourceType} псевдонима репозитория. */
    public static final String REPOSITORY_URL = "REPOSITORY_URL";

    /** {@code sourceType} псевдонима ИТ-системы по коду {@code eam_id}. */
    public static final String IT_SYSTEM_CODE = "IT_SYSTEM_CODE";

    private LookupKeys() {
    }

    /** Псевдоним репозитория SCM по каноническому URL ({@code GitUrls.normalize}). */
    public static SourceKey repositoryUrl(String normalizedUrl) {
        return new SourceKey(SourceSystemCode.SCM, REPOSITORY_URL, normalizedUrl);
    }

    /**
     * Псевдоним ИТ-системы по её коду в EAM ({@code eam_id}, в SCM это {@code systemCode}). Записи с таким ключом
     * нет: проектор достраивает отложенные связи, когда приходит {@code ITSystem} со свойством {@code eamCode}.
     */
    public static SourceKey itSystemCode(String eamCode) {
        return new SourceKey(SourceSystemCode.EAM, IT_SYSTEM_CODE, eamCode);
    }
}
