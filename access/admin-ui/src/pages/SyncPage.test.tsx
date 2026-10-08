import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '../api/apiClient';
import { SourceStatus } from '../api/types';
import { Handler, mockApi, page, withApi } from '../testing/mockApi';
import { SyncPage } from './SyncPage';
import { health } from './sync/SourcesTab';

const zero = {
  RECEIVED: 0, VALIDATED: 0, NORMALIZED: 0, RESOLVED: 0, PROJECTED: 0,
  QUARANTINED: 0, RETRYING: 0, SUPERSEDED: 0, DUPLICATE: 0, IGNORED_OLD_VERSION: 0,
};
const run = { runId: 'r1', startedAt: '2026-10-01T10:00:00Z', endedAt: null, status: 'SUCCEEDED', fetched: 10, applied: 9, failed: 0 };
const source = (name: string, extra: Partial<SourceStatus> = {}): SourceStatus => ({
  source: name,
  checkpoints: [],
  lastSyncRun: run,
  eventsByStatus: { ...zero, PROJECTED: 5 },
  lastProjectedAt: '2026-10-01T10:05:00Z',
  lagSeconds: 125,
  ...extra,
});
const sources = [
  source('eam'),
  source('scm', { eventsByStatus: { ...zero, QUARANTINED: 3 } }),
  source('cmdb', { lastSyncRun: null, lastProjectedAt: null, lagSeconds: null }),
  source('deploymap', { lastSyncRun: { ...run, status: 'FAILED', failed: 2 } }),
];
const event = {
  source: 'urn:corp:eam', eventId: 'e-1', type: 'asset.upserted', subject: 's', sourceType: 'System', sourceId: 'S-1',
  sourceVersion: '1', correlationId: null, syncRunId: 'r1', schemaVersion: '1', status: 'QUARANTINED', attempts: 3,
  errorCode: 'BAD_SCHEMA', payloadRef: 's3://bucket/key', contentHash: 'abc', receivedAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:01:00Z',
};
const dlq = { id: 1, source: 'urn:corp:eam', eventId: 'e-1', errorCode: 'BAD_SCHEMA', reason: '<b>bad</b> field', payloadRef: null, createdAt: '2026-10-01T10:02:00Z', replayedAt: null };

function api(overrides: Record<string, Handler> = {}) {
  return mockApi({
    '/api/sync/sources': () => sources,
    '/api/sync/events/': () => ({ event, errorReason: 'schema mismatch', dlqEntries: [dlq], historyNote: 'no history' }),
    '/api/sync/events': () => page([event], { total: 120 }),
    '/api/sync/dlq': () => page([dlq]),
    '/api/identity/conflicts': () => page([
      { gid: 'g1', property: 'owner', dissentSource: 'scm', dissentType: 'Repo', dissentId: 'R', dissentValue: 'a', masterSource: 'eam', masterType: 'System', masterId: 'S', masterValue: 'b', status: 'OPEN', openedAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', resolvedAt: null },
    ]),
    '/api/identity/candidates': () => page([
      { leftSource: 'eam', leftType: 'System', leftId: 'L1', rightSource: 'cmdb', rightType: 'CI', rightId: 'R1', labelFamily: 'Service', matched: [{ feature: 'name', value: 'billing' }], score: 0.8, status: 'OPEN', firstSeenAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z' },
    ]),
    '/api/audit': () => page([
      { id: 7, operation: 'reconcile', actor: 'admin', status: 'SUCCEEDED', replayId: null, request: {}, requestTruncated: false, result: {}, resultTruncated: false, error: null, startedAt: '2026-10-01T10:00:00Z', finishedAt: null },
    ]),
    ...overrides,
  });
}

function open(mock: ReturnType<typeof api>, tab?: string) {
  render(withApi(mock, <SyncPage />));
  if (tab) fireEvent.click(screen.getByRole('tab', { name: tab }));
}

afterEach(() => {
  jest.useRealTimers();
  jest.clearAllMocks();
});

test('sources screen shows a traffic light per source with run, lag and counters', async () => {
  open(api());

  const table = await screen.findByRole('table', { name: 'Источники синхронизации' });
  const rows = within(table).getAllByRole('row').slice(1);
  expect(rows).toHaveLength(4);
  expect(within(rows[0]).getByText('норма')).toBeTruthy();
  expect(within(rows[0]).getByText('2 мин')).toBeTruthy();
  expect(within(rows[1]).getByText('проблемы')).toBeTruthy();
  expect(rows[1].textContent).toContain('QUARANTINED: 3');
  expect(within(rows[2]).getByText('нет данных')).toBeTruthy();
  expect(within(rows[3]).getByText('проблемы')).toBeTruthy();
});

test('health rules', () => {
  expect(health(sources[0])).toBe('ok');
  expect(health(sources[1])).toBe('bad');
  expect(health(sources[2])).toBe('warn');
  expect(health(sources[3])).toBe('bad');
});

test('sources refresh every 30 seconds and manually', async () => {
  jest.useFakeTimers();
  const mock = api();
  open(mock);
  await act(async () => {});
  const calls = () => mock.get.mock.calls.filter((c) => c[0] === '/api/sync/sources').length;
  expect(calls()).toBe(1);

  await act(async () => {
    jest.advanceTimersByTime(29_000);
  });
  expect(calls()).toBe(1);
  await act(async () => {
    jest.advanceTimersByTime(1_000);
  });
  expect(calls()).toBe(2);
  // Автообновление не сбрасывает таблицу в «Загрузка…».
  expect(screen.queryByText('Загрузка…')).toBeNull();

  fireEvent.click(screen.getByRole('button', { name: 'Обновить' }));
  await act(async () => {});
  expect(calls()).toBe(3);
});

test('sources show error state', async () => {
  open(api({ '/api/sync/sources': () => { throw new ApiError(503, 'x'); } }));

  expect((await screen.findByRole('alert')).textContent).toBe('Ошибка запроса (HTTP 503)');
});

test('events journal sends source, status and period filters and resets the page', async () => {
  const mock = api();
  open(mock, 'Журнал событий');
  await screen.findByRole('table', { name: 'События' });

  fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }));
  await waitFor(() => expect(mock.get).toHaveBeenCalledWith(expect.stringContaining('page=1')));

  fireEvent.change(screen.getByLabelText('Источник'), { target: { value: 'eam' } });
  fireEvent.change(screen.getByLabelText('Статус'), { target: { value: 'QUARANTINED' } });
  fireEvent.change(screen.getByLabelText('С'), { target: { value: '2026-10-01T00:00' } });
  fireEvent.change(screen.getByLabelText('По'), { target: { value: '2026-10-02T00:00' } });

  const expectedFrom = encodeURIComponent(new Date('2026-10-01T00:00').toISOString());
  const expectedTo = encodeURIComponent(new Date('2026-10-02T00:00').toISOString());
  await waitFor(() =>
    expect(mock.get).toHaveBeenLastCalledWith(
      `/api/sync/events?source=eam&status=QUARANTINED&from=${expectedFrom}&to=${expectedTo}&page=0&size=50`,
    ),
  );
});

test('events journal paginates', async () => {
  const mock = api();
  open(mock, 'Журнал событий');
  await screen.findByText('Страница 1 из 3 (всего 120)');

  expect((screen.getByRole('button', { name: 'Назад' }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }));

  await waitFor(() => expect(mock.get).toHaveBeenLastCalledWith('/api/sync/events?page=1&size=50'));
});

test('events journal shows empty state', async () => {
  open(api({ '/api/sync/events': () => page([]) }), 'Журнал событий');

  expect(await screen.findByText('Событий нет')).toBeTruthy();
});

test('event card shows metadata and DLQ records but never payload', async () => {
  const mock = api();
  open(mock, 'Журнал событий');
  fireEvent.click(await screen.findByRole('button', { name: 'e-1' }));

  const card = await screen.findByRole('complementary', { name: 'Карточка события' });
  await within(card).findByText('Причина ошибки: schema mismatch');
  expect(card.textContent).toContain('s3://bucket/key');
  expect(card.textContent).toContain('abc');
  expect(mock.get).toHaveBeenCalledWith('/api/sync/events/urn%3Acorp%3Aeam/e-1');
  // Причина из источника выводится как текст.
  expect(within(card).getByText(/<b>bad<\/b> field/)).toBeTruthy();
});

test('DLQ shows reasons and asks only for open entries by default', async () => {
  const mock = api();
  open(mock, 'DLQ');

  const table = await screen.findByRole('table', { name: 'Записи DLQ' });
  expect(within(table).getByText('<b>bad</b> field')).toBeTruthy();
  expect(mock.get).toHaveBeenCalledWith('/api/sync/dlq?replayed=false&page=0&size=50');

  fireEvent.click(screen.getByLabelText('Показать повторённые'));
  await waitFor(() => expect(mock.get).toHaveBeenLastCalledWith('/api/sync/dlq?page=0&size=50'));
});

test('DLQ shows empty state', async () => {
  open(api({ '/api/sync/dlq': () => page([]) }), 'DLQ');

  expect(await screen.findByText('Записей DLQ нет')).toBeTruthy();
});

test('identity shows conflicts and candidates', async () => {
  open(api(), 'Identity');

  const conflicts = await screen.findByRole('table', { name: 'Конфликты источников' });
  expect(within(conflicts).getByText('owner')).toBeTruthy();
  const candidates = await screen.findByRole('table', { name: 'Кандидаты identity' });
  expect(within(candidates).getByText('name=billing')).toBeTruthy();
});

test('audit shows admin operations', async () => {
  open(api(), 'Аудит');

  const table = await screen.findByRole('table', { name: 'Аудит админ-операций' });
  expect(within(table).getByText('reconcile')).toBeTruthy();
  expect(within(table).getByText('admin')).toBeTruthy();
});

test('audit shows empty state', async () => {
  open(api({ '/api/audit': () => page([]) }), 'Аудит');

  expect(await screen.findByText('Операций нет')).toBeTruthy();
});
