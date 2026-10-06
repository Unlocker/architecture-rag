package io.github.unlocker.archrag.sourcestubs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Метаданные типов EAM для {@code /api/types/{id}/options/?kind=fields|system}. Реальный формат опций в спеке не
 * описан ({@code options: array of object}), поэтому состав ключей ({@code name}, {@code type}, {@code target},
 * {@code multiple}) — допущение «не подтверждено»; когда появится выгрузка, заменить на записанный ответ.
 */
final class EamApiTypeMeta {

  private EamApiTypeMeta() {}

  static String title(String type) {
    return switch (type) {
      case EamApiSeed.PLATFORM_TYPE -> "ИТ-платформы";
      case EamApiSeed.IT_SYSTEM_TYPE -> "ИТ-системы";
      default -> "ИТ-решения";
    };
  }

  static List<Map<String, Object>> options(String type, String kind) {
    if (kind.equals("system")) {
      return List.of(
          field("eam_id", "string", null, false),
          field("eam_name", "string", null, false),
          field("eam_permalink", "string", null, false));
    }
    List<Map<String, Object>> common = List.of(
        field("name", "string", null, false),
        field("status", "select", null, false),
        field("description", "text", null, false));
    List<Map<String, Object>> out = new ArrayList<>(common);
    switch (type) {
      case EamApiSeed.PLATFORM_TYPE -> out.add(field("lifecycle", "select", null, false));
      case EamApiSeed.IT_SYSTEM_TYPE -> {
        out.add(field("criticality", "select", null, false));
        out.add(field("solution", "reference", EamApiSeed.SOLUTION_TYPE, true));
      }
      default -> {
        out.add(field("lifecycle", "select", null, false));
        out.add(field("platform", "reference", EamApiSeed.PLATFORM_TYPE, true));
      }
    }
    return out;
  }

  private static Map<String, Object> field(String name, String type, String target, boolean multiple) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("type", type);
    if (target != null) {
      m.put("target", target);
      m.put("multiple", multiple);
    }
    return m;
  }
}
