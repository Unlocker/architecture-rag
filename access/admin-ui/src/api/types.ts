/** Формы ответов read-only API консоли (см. GraphResponses и pg.Rows в access/admin-console). */

export interface Count {
  name: string;
  count: number;
}

export interface Stats {
  nodes: Count[];
  relations: Count[];
  lastUpdatedAt: string | null;
}

export interface AssetHit {
  gid: string;
  type: string;
  name: string;
  score: number;
  matchType: string;
  sources: string[];
  lastSeenAt: string | null;
}

export interface SearchResponse {
  items: AssetHit[];
  truncated: boolean;
}

/** Запись источника с ребром ASSERTS; значений свойств актива в ней нет. */
export interface SourceRecordInfo {
  source: string;
  sourceType: string;
  sourceId: string;
  sourceVersion: string | null;
  fetchedAt: string | null;
  contentHash: string | null;
  active: boolean | null;
  deletedAt: string | null;
  authority: string | number | null;
  confidence: number | null;
  conflicts: string[] | null;
}

export interface NodeCard {
  gid: string;
  type: string;
  properties: Record<string, unknown>;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
  deletedAt: string | null;
  isCurrent: boolean;
  sources: SourceRecordInfo[];
}

export interface GraphNode {
  gid: string;
  type: string;
  name: string;
  isCurrent: boolean;
  distance: number;
}

export interface GraphEdge {
  type: string;
  source: string;
  target: string;
}

export interface Neighborhood {
  center: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Checkpoint {
  consumer: string;
  cursor: string | null;
  updatedAt: string | null;
}

export interface SyncRunInfo {
  runId: string;
  startedAt: string | null;
  endedAt: string | null;
  status: string | null;
  fetched: number | null;
  applied: number | null;
  failed: number | null;
}

export interface SourceStatus {
  source: string;
  checkpoints: Checkpoint[];
  lastSyncRun: SyncRunInfo | null;
  eventsByStatus: Record<string, number>;
  lastProjectedAt: string | null;
  lagSeconds: number | null;
}

/** Событие inbox: только метаданные, payload консоль не получает. */
export interface SyncEvent {
  source: string;
  eventId: string;
  type: string;
  subject: string | null;
  sourceType: string | null;
  sourceId: string | null;
  sourceVersion: string | null;
  correlationId: string | null;
  syncRunId: string | null;
  schemaVersion: string | null;
  status: string;
  attempts: number;
  errorCode: string | null;
  payloadRef: string | null;
  contentHash: string | null;
  receivedAt: string;
  updatedAt: string;
}

export interface DlqEntry {
  id: number;
  source: string;
  eventId: string;
  errorCode: string | null;
  reason: string | null;
  payloadRef: string | null;
  createdAt: string;
  replayedAt: string | null;
}

export interface EventDetail {
  event: SyncEvent;
  errorReason: string | null;
  dlqEntries: DlqEntry[];
  historyNote: string;
}

export interface Conflict {
  gid: string;
  property: string;
  dissentSource: string;
  dissentType: string;
  dissentId: string;
  dissentValue: string | null;
  masterSource: string;
  masterType: string;
  masterId: string;
  masterValue: string | null;
  status: string;
  openedAt: string;
  updatedAt: string;
  resolvedAt: string | null;
}

export interface MatchedFeature {
  feature: string;
  value: unknown;
}

export interface Candidate {
  leftSource: string;
  leftType: string;
  leftId: string;
  rightSource: string;
  rightType: string;
  rightId: string;
  labelFamily: string;
  matched: MatchedFeature[] | null;
  score: number;
  status: string;
  firstSeenAt: string;
  updatedAt: string;
}

export interface Audit {
  id: number;
  operation: string;
  actor: string | null;
  status: string;
  replayId: string | null;
  request: unknown;
  requestTruncated: boolean;
  result: unknown;
  resultTruncated: boolean;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}
