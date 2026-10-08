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
] as const;

export const SOURCES = ['eam', 'scm', 'cmdb', 'deploymap'] as const;

export const EVENT_STATUSES = [
  'RECEIVED',
  'VALIDATED',
  'NORMALIZED',
  'RESOLVED',
  'PROJECTED',
  'QUARANTINED',
  'RETRYING',
  'SUPERSEDED',
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

/** Актив, не виденный дольше этого срока, помечается как устаревший. */
export const STALE_AFTER_MS = 7 * 24 * 60 * 60 * 1000;

/** Период автообновления раздела «Источники». */
export const SOURCES_REFRESH_MS = 30_000;
