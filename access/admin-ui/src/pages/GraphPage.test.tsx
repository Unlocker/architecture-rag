import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import cytoscape from 'cytoscape';
import { ApiError } from '../api/apiClient';
import { Handler, mockApi, withApi } from '../testing/mockApi';
import { GraphPage } from './GraphPage';
import { isStale } from './graph/AssetCard';
import { NodeCard } from '../api/types';

jest.mock('cytoscape', () => {
  const cy = { on: jest.fn(), destroy: jest.fn() };
  return { __esModule: true, default: jest.fn(() => cy) };
});

const GID = '11111111-1111-4111-8111-111111111111';
const OTHER = '22222222-2222-4222-8222-222222222222';

const stats = {
  nodes: [{ name: 'Service', count: 7 }, { name: 'Team', count: 2 }],
  relations: [{ name: 'OWNED_BY', count: 5 }],
  lastUpdatedAt: '2026-10-01T10:00:00Z',
};
const hit = { gid: GID, type: 'Service', name: 'Billing', score: 1000, matchType: 'EXACT', sources: ['eam'], lastSeenAt: null };
const card = {
  gid: GID,
  type: 'Service',
  properties: { name: 'Billing', description: '<img src=x onerror=alert(1)>' },
  firstSeenAt: '2026-01-01T00:00:00Z',
  lastSeenAt: '2026-01-02T00:00:00Z',
  deletedAt: null,
  isCurrent: true,
  sources: [
    { source: 'eam', sourceType: 'System', sourceId: 'S-1', sourceVersion: '12', fetchedAt: '2026-01-02T00:00:00Z', contentHash: 'h', active: true, deletedAt: null, authority: 'MASTER', confidence: 1, conflicts: null },
    { source: 'scm', sourceType: 'Repo', sourceId: 'R-1', sourceVersion: '3', fetchedAt: '2026-01-01T00:00:00Z', contentHash: 'h2', active: false, deletedAt: '2026-01-03T00:00:00Z', authority: 'HIGH', confidence: 0.5, conflicts: ['owner'] },
  ],
};
const hood = (truncated: boolean) => ({
  center: GID,
  nodes: [
    { gid: GID, type: 'Service', name: 'Billing', isCurrent: true, distance: 0 },
    { gid: OTHER, type: 'Team', name: 'Payments', isCurrent: true, distance: 1 },
  ],
  edges: [{ type: 'OWNED_BY', source: GID, target: OTHER }],
  truncated,
});

function api(overrides: Record<string, Handler> = {}) {
  return mockApi({
    '/api/graph/stats': () => stats,
    '/api/graph/search': () => ({ items: [hit], truncated: false }),
    '/api/graph/nodes/': (p) => (p.includes('/neighborhood') ? hood(false) : card),
    ...overrides,
  });
}

async function openCard(mock: ReturnType<typeof api>) {
  render(withApi(mock, <GraphPage />));
  fireEvent.change(screen.getByLabelText('Запрос'), { target: { value: 'billing' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Billing' }));
}

afterEach(() => jest.clearAllMocks());

test('summary shows counters per label and relation type and last update', async () => {
  render(withApi(api(), <GraphPage />));

  const labels = await screen.findByRole('table', { name: 'Узлы по меткам' });
  expect(within(labels).getByText('Service').nextSibling?.textContent).toBe('7');
  const relations = screen.getByRole('table', { name: 'Связи по типам' });
  expect(within(relations).getByText('OWNED_BY').nextSibling?.textContent).toBe('5');
  const time = document.querySelector('time[datetime="2026-10-01T10:00:00Z"]');
  expect(time?.getAttribute('title')).toBe('2026-10-01T10:00:00Z');
});

test('summary shows empty and error states', async () => {
  const empty = api({ '/api/graph/stats': () => ({ nodes: [], relations: [], lastUpdatedAt: null }) });
  const { unmount } = render(withApi(empty, <GraphPage />));
  expect(await screen.findByText('Нет данных')).toBeTruthy();
  unmount();

  const failing = api({
    '/api/graph/stats': () => {
      throw new ApiError(500, 'boom secret');
    },
  });
  render(withApi(failing, <GraphPage />));
  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('Ошибка запроса (HTTP 500)');
});

test('search sends query and type filter and lists name, gid, score', async () => {
  const mock = api();
  render(withApi(mock, <GraphPage />));

  fireEvent.change(screen.getByLabelText('Запрос'), { target: { value: 'billing' } });
  fireEvent.click(screen.getByLabelText('Service'));
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

  const table = await screen.findByRole('table', { name: 'Результаты поиска' });
  expect(within(table).getByText(GID)).toBeTruthy();
  expect(within(table).getByText('1000')).toBeTruthy();
  expect(mock.get).toHaveBeenCalledWith('/api/graph/search?q=billing&types=Service');
});

test('search with no hits shows empty state', async () => {
  render(withApi(api({ '/api/graph/search': () => ({ items: [], truncated: false }) }), <GraphPage />));
  fireEvent.change(screen.getByLabelText('Запрос'), { target: { value: 'nothing' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

  expect(await screen.findByText('Ничего не найдено')).toBeTruthy();
});

test('blank query is not sent', () => {
  const mock = api();
  render(withApi(mock, <GraphPage />));
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

  expect(mock.get).not.toHaveBeenCalledWith(expect.stringContaining('/search'));
});

test('asset card shows properties as text, lifecycle and provenance with tombstone', async () => {
  await openCard(api());

  const props = await screen.findByRole('table', { name: 'Свойства' });
  // Текст источника выводится как текст, а не как разметка.
  expect(within(props).getByText('<img src=x onerror=alert(1)>')).toBeTruthy();
  expect(document.querySelector('img')).toBeNull();
  const lifecycle = screen.getByRole('list', { name: 'Жизненный цикл' });
  expect(lifecycle.textContent).toContain('isCurrent: true');
  const records = screen.getByRole('table', { name: 'Записи источников' });
  expect(within(records).getByText('eam / System / S-1')).toBeTruthy();
  expect(within(records).getByText('tombstone')).toBeTruthy();
  expect(within(records).getByText('активна')).toBeTruthy();
  expect(within(records).getByText('расхождения: owner')).toBeTruthy();
});

test('card marks closed and stale assets', () => {
  const base = card as unknown as NodeCard;
  expect(isStale(base, Date.parse('2026-01-03T00:00:00Z'))).toBe(false);
  expect(isStale(base, Date.parse('2026-02-01T00:00:00Z'))).toBe(true);
  expect(isStale({ ...base, isCurrent: false }, Date.parse('2026-02-01T00:00:00Z'))).toBe(false);
});

test('asset card shows error when asset is not found', async () => {
  await openCard(
    api({
      '/api/graph/nodes/': (p) => {
        if (p.includes('/neighborhood')) return hood(false);
        throw new ApiError(404, 'nf');
      },
    }),
  );

  expect((await screen.findAllByRole('alert'))[0].textContent).toBe('Ошибка запроса (HTTP 404)');
});

test('neighborhood draws the graph, colors by label and opens a clicked node card', async () => {
  const mock = api();
  await openCard(mock);

  await screen.findByTestId('graph-canvas');
  const options = (cytoscape as unknown as jest.Mock).mock.calls[0][0];
  const colors = Object.fromEntries(
    options.elements.filter((e: any) => !e.data.source).map((e: any) => [e.data.id, e.data.color]),
  );
  expect(colors[GID]).not.toEqual(colors[OTHER]);
  expect(mock.get).toHaveBeenCalledWith(`/api/graph/nodes/${GID}/neighborhood?depth=1`);

  const cy = (cytoscape as unknown as jest.Mock).mock.results[0].value;
  const tap = cy.on.mock.calls.find((c: unknown[]) => c[0] === 'tap')[2];
  tap({ target: { id: () => OTHER } });

  await waitFor(() => expect(mock.get).toHaveBeenCalledWith(`/api/graph/nodes/${OTHER}`));
});

test('neighborhood depth and relation type filters are sent to the API', async () => {
  const mock = api();
  await openCard(mock);
  await screen.findByTestId('graph-canvas');

  fireEvent.change(screen.getByLabelText('Глубина'), { target: { value: '2' } });
  await waitFor(() => expect(mock.get).toHaveBeenCalledWith(`/api/graph/nodes/${GID}/neighborhood?depth=2`));
  fireEvent.click(screen.getByLabelText('OWNED_BY'));

  await waitFor(() =>
    expect(mock.get).toHaveBeenCalledWith(`/api/graph/nodes/${GID}/neighborhood?depth=2&relTypes=OWNED_BY`),
  );
});

test('truncated neighborhood shows a banner, complete one does not', async () => {
  await openCard(api({ '/api/graph/nodes/': (p) => (p.includes('/neighborhood') ? hood(true) : card) }));
  expect((await screen.findByText(/показана не полностью/)).getAttribute('role')).toBe('status');
});

test('complete neighborhood has no truncated banner', async () => {
  await openCard(api());
  await screen.findByTestId('graph-canvas');

  expect(screen.queryByText(/показана не полностью/)).toBeNull();
});

test('stale search response does not overwrite a newer one', async () => {
  let release: (v: unknown) => void = () => undefined;
  let calls = 0;
  const mock = api({
    '/api/graph/search': () => {
      calls += 1;
      if (calls === 1) return new Promise((r) => (release = r));
      return { items: [{ ...hit, name: 'Newer' }], truncated: false };
    },
  });
  render(withApi(mock, <GraphPage />));
  fireEvent.change(screen.getByLabelText('Запрос'), { target: { value: 'a' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
  fireEvent.change(screen.getByLabelText('Запрос'), { target: { value: 'b' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
  await screen.findByRole('button', { name: 'Newer' });

  await act(async () => release({ items: [{ ...hit, name: 'Older' }], truncated: false }));

  expect(screen.queryByRole('button', { name: 'Older' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Newer' })).toBeTruthy();
});
