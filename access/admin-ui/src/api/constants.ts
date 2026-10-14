/**
 * Allowlist'ы API консоли. Продублированы из AssetTemplates / ConsoleTemplates / SyncSources:
 * UI не зависит от Java-модулей, а бэкенд всё равно отвергает неизвестные значения (400).
 */
export const ASSET_TYPES = [
  'ITSystem',
  'Service',
  'Repository',
  'Team',
  'Environment',
  'Deployment',
  'ComputeInstance',
] as const;

export const RELATION_TYPES = [
  'DECOMPOSED_INTO',
  'IMPLEMENTED_IN',
  'HAS_DEPLOYMENT',
  'IN_ENVIRONMENT',
  'RUNS_ON',
  'DEPENDS_ON',
  'OWNED_BY',
  'HOSTED_ON',
  'DEPLOYED_FROM',
] as const;

export const SOURCES = ['eam', 'scm', 'cmdb', 'deploymap', 'manual'] as const;

export const EVENT_STATUSES = [
  'RECEIVED',
  'VALIDATED',
  'NORMALIZED',
  'RESOLVED',
  'PROJECTED',
  'QUARANTINED',
  'RETRYING',
  'DUPLICATE',
  'IGNORED_OLD_VERSION',
] as const;

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
