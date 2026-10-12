package io.github.unlocker.archrag.sourcestubs;

/**
 * ВРЕМЕННЫЙ собственный формат deploy map и Helm charts для заглушки.
 *
 * <p>Владелец ещё не определил, где хранятся deploy map и charts и в каком формате. Здесь
 * зафиксирован минимум, достаточный для среза {@code Deployment -> Environment -> ComputeInstance};
 * после решения владельца формат и адаптер заменяются, а не расширяются.
 *
 * <ul>
 *   <li>{@code ENVIRONMENT}: {@code name}
 *   <li>{@code DEPLOYMENT}: {@code service} (id сервиса SCM), {@code environment} (id
 *       ENVIRONMENT), {@code chart}, {@code chartVersion}, {@code hosts} (hostname'ы CMDB)
 * </ul>
 *
 * <p>Формат {@link #FORMAT_V1} ({@code deploymap-poc-1}) добавляет у {@code DEPLOYMENT} ключ {@link
 * #CHART_META} — метаданные {@code Chart.yaml}: {@code name}, {@code version}, {@code appVersion} и
 * {@code annotations} (map строк) с ключами {@link #ANNOTATION_EAM_SYSTEM_ID}, {@link
 * #ANNOTATION_GIT_REPO_URL}, {@link #ANNOTATION_GIT_COMMIT}. Строковые {@code chart} и {@code
 * chartVersion} сохраняют тип и дублируют {@code chartMeta.name} и {@code chartMeta.version}, так
 * что payload {@link #FORMAT} остаётся валидным и разбирается без изменений. Это формат payload, а
 * не {@code schemaVersion} события.
 */
public final class DeployMapFormat {

  /** Идентификатор временного формата; кладётся в payload как {@code format}. */
  public static final String FORMAT = "deploymap-poc-0";

  /** Формат с chart-метаданными у {@code DEPLOYMENT}; {@link #FORMAT} поддерживается до перехода. */
  public static final String FORMAT_V1 = "deploymap-poc-1";

  /** Ключ payload {@code DEPLOYMENT} с метаданными {@code Chart.yaml} (только {@link #FORMAT_V1}). */
  public static final String CHART_META = "chartMeta";

  /** Ключ аннотации: id системы EAM ({@code id} объекта, не {@code eam_id}). */
  public static final String ANNOTATION_EAM_SYSTEM_ID = "archrag.io/eam-system-id";

  /** Ключ аннотации: URL Git-репозитория; нормализуется {@code GitUrls.normalize}. */
  public static final String ANNOTATION_GIT_REPO_URL = "archrag.io/git-repo-url";

  /** Ключ аннотации: commit, 40 hex в нижнем регистре. */
  public static final String ANNOTATION_GIT_COMMIT = "archrag.io/git-commit";

  public static final String ENVIRONMENT = "ENVIRONMENT";
  public static final String DEPLOYMENT = "DEPLOYMENT";

  private DeployMapFormat() {}
}
