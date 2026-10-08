import { ApiClient } from './apiClient';
import {
  Audit,
  Candidate,
  Conflict,
  DlqEntry,
  EventDetail,
  NodeCard,
  Neighborhood,
  Page,
  SearchResponse,
  SourceStatus,
  Stats,
  SyncEvent,
} from './types';

type Params = Record<string, string | number | boolean | string[] | null | undefined>;

/** Query string без пустых значений; массивы повторяют ключ. */
export function query(params: Params): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      value.forEach((v) => search.append(key, v));
    } else {
      search.append(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export interface PageParams {
  page: number;
  size: number;
}

export interface EventFilter extends PageParams {
  source?: string;
  status?: string;
  from?: string;
  to?: string;
}

/** Типизированные вызовы read-only API консоли. */
export function endpoints(api: ApiClient) {
  return {
    stats: () => api.get<Stats>('/api/graph/stats'),
    search: (q: string, types: string[]) =>
      api.get<SearchResponse>(`/api/graph/search${query({ q, types })}`),
    node: (gid: string) => api.get<NodeCard>(`/api/graph/nodes/${encodeURIComponent(gid)}`),
    neighborhood: (gid: string, depth: number, relTypes: string[]) =>
      api.get<Neighborhood>(
        `/api/graph/nodes/${encodeURIComponent(gid)}/neighborhood${query({ depth, relTypes })}`,
      ),
    sources: () => api.get<SourceStatus[]>('/api/sync/sources'),
    events: (f: EventFilter) => api.get<Page<SyncEvent>>(`/api/sync/events${query({ ...f })}`),
    event: (source: string, eventId: string) =>
      api.get<EventDetail>(
        `/api/sync/events/${encodeURIComponent(source)}/${encodeURIComponent(eventId)}`,
      ),
    dlq: (p: PageParams & { source?: string; replayed?: boolean }) =>
      api.get<Page<DlqEntry>>(`/api/sync/dlq${query({ ...p })}`),
    conflicts: (p: PageParams & { status?: string }) =>
      api.get<Page<Conflict>>(`/api/identity/conflicts${query({ ...p })}`),
    candidates: (p: PageParams & { status?: string }) =>
      api.get<Page<Candidate>>(`/api/identity/candidates${query({ ...p })}`),
    audit: (p: PageParams & { operation?: string; status?: string }) =>
      api.get<Page<Audit>>(`/api/audit${query({ ...p })}`),
  };
}

export type Endpoints = ReturnType<typeof endpoints>;
