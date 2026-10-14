import allowlists from './allowlists.json';

/**
 * Allowlist'ы API консоли лежат в allowlists.json. Их равенство AssetTemplates / ConsoleTemplates / SyncSources
 * проверяет AdminUiAllowlistsContractTest в admin-console; бэкенд всё равно отвергает неизвестные значения (400).
 */
export const ASSET_TYPES: readonly string[] = allowlists.ASSET_TYPES;

export const RELATION_TYPES: readonly string[] = allowlists.RELATION_TYPES;

export const SOURCES: readonly string[] = allowlists.SOURCES;

export const EVENT_STATUSES: readonly string[] = allowlists.EVENT_STATUSES;

/** Цвет узла по метке (графа и бейджей). */
export const LABEL_COLORS: Record<string, string> = {
  ITSystem: '#1f77b4',
  Service: '#2ca02c',
  Repository: '#9467bd',
  Team: '#ff7f0e',
  Environment: '#8c564b',
  Deployment: '#17becf',
  ComputeInstance: '#7f7f7f',
};

export const DEFAULT_LABEL_COLOR = '#bcbd22';

/** Период автообновления раздела «Источники». */
export const SOURCES_REFRESH_MS = 30_000;

/** Отставание проекции источника: выше — жёлтый, выше второго порога — красный. */
export const LAG_WARN_SECONDS = 15 * 60;
export const LAG_BAD_SECONDS = 60 * 60;
