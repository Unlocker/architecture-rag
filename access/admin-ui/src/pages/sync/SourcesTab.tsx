import { useApi } from '../../api/ApiContext';
import { LAG_BAD_SECONDS, LAG_WARN_SECONDS, SOURCES_REFRESH_MS } from '../../api/constants';
import { endpoints } from '../../api/endpoints';
import { SourceStatus } from '../../api/types';
import { Badge, Loadable, Time } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

const PROBLEM_STATUSES = ['QUARANTINED', 'RETRYING'];

/** Светофор источника: красный — проблемные события, упавший прогон или большой lag; жёлтый — нет данных или заметный lag. */
export function health(s: SourceStatus): 'ok' | 'warn' | 'bad' {
  const problems = PROBLEM_STATUSES.some((st) => (s.eventsByStatus[st] ?? 0) > 0);
  const failedRun = s.lastSyncRun?.status === 'FAILED' || (s.lastSyncRun?.failed ?? 0) > 0;
  const lag = s.lagSeconds ?? 0;
  if (problems || failedRun || lag > LAG_BAD_SECONDS) return 'bad';
  if (s.lastProjectedAt === null || s.lastSyncRun === null || lag > LAG_WARN_SECONDS) return 'warn';
  return 'ok';
}

function formatLag(seconds: number | null): string {
  if (seconds === null) return '—';
  if (seconds < 60) return `${seconds} с`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} мин`;
  return `${Math.floor(seconds / 3600)} ч`;
}

/** Таблица-светофор по источникам; обновляется раз в 30 секунд и вручную. */
export function SourcesTab() {
  const api = endpoints(useApi());
  const { state, reload } = useAsync(() => api.sources(), [], SOURCES_REFRESH_MS);
  return (
    <section aria-label="Источники">
      <button type="button" onClick={reload}>
        Обновить
      </button>
      <Loadable state={state} isEmpty={(s) => s.length === 0}>
        {(sources) => (
          <table aria-label="Источники синхронизации">
            <thead>
              <tr>
                <th>Источник</th>
                <th>Состояние</th>
                <th>Последний SyncRun</th>
                <th>Lag</th>
                <th>События по статусам</th>
                <th>Ошибки</th>
              </tr>
            </thead>
            <tbody>
              {sources.map((s) => {
                const h = health(s);
                const run = s.lastSyncRun;
                const errors = PROBLEM_STATUSES.map((st) => `${st}: ${s.eventsByStatus[st] ?? 0}`);
                return (
                  <tr key={s.source}>
                    <td>{s.source}</td>
                    <td>
                      <Badge kind={h}>{h === 'ok' ? 'норма' : h === 'warn' ? 'нет данных' : 'проблемы'}</Badge>
                    </td>
                    <td>
                      {run ? (
                        <>
                          {run.status ?? '—'} <Time value={run.startedAt} /> (fetched {run.fetched ?? '—'}, applied{' '}
                          {run.applied ?? '—'}, failed {run.failed ?? '—'})
                        </>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>{formatLag(s.lagSeconds)}</td>
                    <td>
                      {Object.entries(s.eventsByStatus)
                        .filter(([, n]) => n > 0)
                        .map(([st, n]) => `${st}: ${n}`)
                        .join(', ') || '—'}
                    </td>
                    <td>{errors.join(', ')}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Loadable>
    </section>
  );
}
