package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;

/**
 * Ответ {@code find_runtime_footprint}: где развёрнута система и насколько фактам можно верить.
 *
 * <p>Только действующие факты. Время — ISO-8601 UTC. Provenance узла — {@code lastSeenAt} и активные
 * {@code sources}. У рёбер {@code lastSeenAt} нет: возвращается {@link EdgeProvenance} (кто
 * утверждает связь, у темпоральных {@code RUNS_ON} ещё {@code validFrom}); свежесть связи оценивается
 * по её конечному узлу.
 *
 * @param systemGid gid системы
 * @param systemName имя системы
 * @param systemLastSeenAt когда систему видели в последний раз
 * @param systemStale система устарела
 * @param systemSources активные утверждения источников о системе
 * @param environment фильтр окружения после strip; {@code null} — все окружения
 * @param services сервисы системы; сервис без развёртываний в окружении даёт пустые {@code deployments}
 * @param staleAfter порог устаревания, ISO-8601 duration
 * @param warnings по строке на устаревший узел
 * @param truncated результат обрезан бюджетом (последняя группа может быть неполной)
 */
public record FindRuntimeFootprintResult(
    String systemGid,
    String systemName,
    String systemLastSeenAt,
    boolean systemStale,
    List<FactSource> systemSources,
    String environment,
    List<ServiceFootprint> services,
    String staleAfter,
    List<String> warnings,
    boolean truncated) {

  /** Сервис и его действующие развёртывания. */
  public record ServiceFootprint(
      String gid,
      String name,
      String lastSeenAt,
      boolean stale,
      List<FactSource> sources,
      List<DeploymentFootprint> deployments) {}

  /** Развёртывание в окружении; {@code environment} — код окружения. */
  public record DeploymentFootprint(
      String gid,
      String name,
      String version,
      String status,
      String environment,
      String lastSeenAt,
      boolean stale,
      List<FactSource> sources,
      EdgeProvenance hasDeployment,
      List<ComputeFootprint> compute) {}

  /** Вычислительный узел; {@code type}: VirtualMachine, PhysicalServer или ComputeInstance. */
  public record ComputeFootprint(
      String gid,
      String type,
      String hostname,
      String state,
      String lastSeenAt,
      boolean stale,
      List<FactSource> sources,
      EdgeProvenance runsOn,
      HostFootprint hostedOn) {}

  /** Физический сервер под VM; {@code null} у ComputeInstance и у PhysicalServer как прямой цели. */
  public record HostFootprint(
      String gid,
      String hostname,
      String state,
      String lastSeenAt,
      boolean stale,
      List<FactSource> sources,
      EdgeProvenance hostedOn) {}

  /** Кто утверждает связь; {@code validFrom} — только у темпоральных связей, иначе {@code null}. */
  public record EdgeProvenance(String source, String sourceType, String sourceId, String validFrom) {}

  /** Активное утверждение источника об узле. */
  public record FactSource(String source, String sourceType, String sourceId, String authority) {}
}
