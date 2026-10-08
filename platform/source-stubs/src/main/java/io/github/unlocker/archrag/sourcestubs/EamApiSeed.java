package io.github.unlocker.archrag.sourcestubs;

import static io.github.unlocker.archrag.sourcestubs.StubSources.fields;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Seed заглушки EAM в формате реального API: вертикальный срез {@code ITSystem -> Solution -> Platform}.
 *
 * <p>Ключ объекта — целочисленный {@code id}. Системное поле {@code eam_id} ИТ-систем совпадает с {@code systemCode}
 * сервисов SCM в {@link StubSources#scm} ({@code EAM-1042}, {@code EAM-2001}), поэтому данные SCM менять не нужно:
 * {@code systemCode -> eam_id -> id}. Имена типов, поля {@code attrs} и словари — допущения «не подтверждено»
 * ({@code docs/06-eam-integration.md}).
 */
public final class EamApiSeed {

  /** Тип EAM для ИТ-платформы. */
  public static final String PLATFORM_TYPE = "platforms";
  /** Тип EAM для ИТ-системы. */
  public static final String IT_SYSTEM_TYPE = "itsystems";
  /**
   * Тип EAM для «ИТ-решения»: допущение «не подтверждено» (кандидаты {@code itproducts}, {@code msys}, {@code okita},
   * {@code aknsd}). Единственное место выбора: смена константы переключает заглушку на другой тип.
   */
  public static final String SOLUTION_TYPE = "itproducts";

  /** Id типов в {@code /api/types/{id}/} — допущение. */
  static final Map<String, Long> TYPE_IDS = Map.of(PLATFORM_TYPE, 11L, SOLUTION_TYPE, 13L, IT_SYSTEM_TYPE, 12L);

  public static final long PLATFORM_KUBERNETES = 3101;
  public static final long PLATFORM_POSTGRES = 3102;
  public static final long SOLUTION_CARD_PROCESSING = 7201;
  public static final long SOLUTION_LEDGER_ENGINE = 7202;
  public static final long SYSTEM_PAYMENTS_CORE = 5101;
  public static final long SYSTEM_LEDGER = 5102;

  private EamApiSeed() {}

  /** Заглушка с seed-данными; часы задают {@code ctime}/{@code mtime}. */
  public static EamApiStore seeded(Clock clock) {
    EamApiStore s = new EamApiStore(clock);
    s.upsert(PLATFORM_TYPE, PLATFORM_KUBERNETES, fields("name", "Kubernetes Platform", "status", "ACTIVE",
        "lifecycle", "PRODUCTION", "description", "Shared container runtime"));
    s.upsert(PLATFORM_TYPE, PLATFORM_POSTGRES, fields("name", "PostgreSQL Platform", "status", "ACTIVE",
        "lifecycle", "PRODUCTION", "description", "Managed relational database"));
    s.upsert(SOLUTION_TYPE, SOLUTION_CARD_PROCESSING, fields("name", "Card Processing", "status", "ACTIVE",
        "lifecycle", "PRODUCTION", "description", "Card payments processing solution",
        "platform", List.of(PLATFORM_KUBERNETES)));
    s.upsert(SOLUTION_TYPE, SOLUTION_LEDGER_ENGINE, fields("name", "Ledger Engine", "status", "ACTIVE",
        "lifecycle", "PRODUCTION", "description", "Double-entry ledger solution",
        "platform", List.of(PLATFORM_KUBERNETES, PLATFORM_POSTGRES)));
    s.upsert(IT_SYSTEM_TYPE, SYSTEM_PAYMENTS_CORE, fields("name", "Payments Core", "eam_id", "EAM-1042",
        "status", "ACTIVE", "criticality", "HIGH", "description", "Payments processing system",
        "solution", List.of(SOLUTION_CARD_PROCESSING)));
    s.upsert(IT_SYSTEM_TYPE, SYSTEM_LEDGER, fields("name", "Ledger", "eam_id", "EAM-2001",
        "status", "ACTIVE", "criticality", "HIGH", "description", "General ledger system",
        "solution", List.of(SOLUTION_LEDGER_ENGINE)));
    return s;
  }
}
