package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;

/**
 * Ответ {@code find_runtime_footprint}: где развёрнута система.
 *
 * <p>Содержит только действующие факты. Закрытая система даёт пустой {@code deployments} и
 * {@code isCurrent=false}.
 *
 * @param systemGid gid системы
 * @param systemName имя системы
 * @param isCurrent система действует
 * @param deployments развёртывания, отсортированные по окружению, сервису и имени
 * @param truncated список обрезан бюджетом
 */
public record FindRuntimeFootprintResult(
    String systemGid,
    String systemName,
    boolean isCurrent,
    List<Deployment> deployments,
    boolean truncated) {

  /**
   * Развёртывание сервиса в окружении.
   *
   * @param gid gid развёртывания
   * @param name имя развёртывания
   * @param version версия или {@code null}
   * @param serviceGid gid сервиса
   * @param serviceName имя сервиса
   * @param environment код окружения
   * @param instances вычислительные узлы, на которых работает развёртывание (может быть пуст)
   */
  public record Deployment(
      String gid,
      String name,
      String version,
      String serviceGid,
      String serviceName,
      String environment,
      List<Instance> instances) {}

  /**
   * Вычислительный узел.
   *
   * @param gid gid узла
   * @param type самая узкая метка: VirtualMachine, PhysicalServer или ComputeInstance
   * @param hostname имя хоста
   * @param state состояние или {@code null}
   * @param hostedOn физический сервер, на котором размещена VM, или {@code null}
   */
  public record Instance(String gid, String type, String hostname, String state, HostedOn hostedOn) {}

  /**
   * Физический сервер под VM.
   *
   * @param gid gid сервера
   * @param hostname имя хоста
   * @param serialNumber серийный номер или {@code null}
   */
  public record HostedOn(String gid, String hostname, String serialNumber) {}
}
