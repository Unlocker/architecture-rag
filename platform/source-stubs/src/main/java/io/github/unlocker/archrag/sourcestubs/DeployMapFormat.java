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
 */
public final class DeployMapFormat {

  /** Идентификатор временного формата; кладётся в payload как {@code format}. */
  public static final String FORMAT = "deploymap-poc-0";

  public static final String ENVIRONMENT = "ENVIRONMENT";
  public static final String DEPLOYMENT = "DEPLOYMENT";

  private DeployMapFormat() {}
}
