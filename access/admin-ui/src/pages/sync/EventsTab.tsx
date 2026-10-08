import { useState } from 'react';
import { useApi } from '../../api/ApiContext';
import { EVENT_STATUSES, SOURCES } from '../../api/constants';
import { endpoints } from '../../api/endpoints';
import { Loadable, Pager, Select, text, Time, toIso } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

const PAGE_SIZE = 50;

function EventDetailCard({ source, eventId, onClose }: { source: string; eventId: string; onClose: () => void }) {
  const api = endpoints(useApi());
  const { state } = useAsync(() => api.event(source, eventId), [source, eventId]);
  return (
    <aside aria-label="Карточка события">
      <button type="button" onClick={onClose}>
        Закрыть
      </button>
      <Loadable state={state}>
        {({ event, errorReason, dlqEntries, historyNote }) => (
          <>
            <table>
              <tbody>
                {Object.entries(event).map(([k, v]) => (
                  <tr key={k}>
                    <th>{k}</th>
                    <td>{k === 'receivedAt' || k === 'updatedAt' ? <Time value={String(v)} /> : text(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>Причина ошибки: {text(errorReason)}</p>
            <p>{historyNote}</p>
            <ul aria-label="Записи DLQ события">
              {dlqEntries.map((d) => (
                <li key={d.id}>
                  {text(d.errorCode)}: {text(d.reason)} (<Time value={d.createdAt} />)
                </li>
              ))}
            </ul>
          </>
        )}
      </Loadable>
    </aside>
  );
}

/** Журнал inbox-событий: фильтры по источнику, статусу и периоду, пагинация, карточка без payload. */
export function EventsTab() {
  const api = endpoints(useApi());
  const [source, setSource] = useState('');
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<{ source: string; eventId: string } | null>(null);

  const fromIso = toIso(from);
  const toIsoValue = toIso(to);
  const { state } = useAsync(
    () => api.events({ source, status, from: fromIso, to: toIsoValue, page, size: PAGE_SIZE }),
    [source, status, fromIso, toIsoValue, page],
  );

  function filter<T>(set: (v: T) => void) {
    return (v: T) => {
      set(v);
      setPage(0);
    };
  }

  return (
    <section aria-label="Журнал событий">
      <div className="filters">
        <Select label="Источник" value={source} options={SOURCES} onChange={filter(setSource)} />
        <Select label="Статус" value={status} options={EVENT_STATUSES} onChange={filter(setStatus)} />
        <label>
          С{' '}
          <input type="datetime-local" aria-label="С" value={from} onChange={(e) => filter(setFrom)(e.target.value)} />
        </label>
        <label>
          По{' '}
          <input type="datetime-local" aria-label="По" value={to} onChange={(e) => filter(setTo)(e.target.value)} />
        </label>
      </div>
      <Loadable state={state} isEmpty={(p) => p.items.length === 0} emptyText="Событий нет">
        {(p) => (
          <>
            <table aria-label="События">
              <thead>
                <tr>
                  <th>Получено</th>
                  <th>Источник</th>
                  <th>eventId</th>
                  <th>Тип</th>
                  <th>Статус</th>
                  <th>Код ошибки</th>
                </tr>
              </thead>
              <tbody>
                {p.items.map((e) => (
                  <tr key={`${e.source}|${e.eventId}`}>
                    <td>
                      <Time value={e.receivedAt} />
                    </td>
                    <td>{e.source}</td>
                    <td>
                      <button
                        type="button"
                        className="link"
                        onClick={() => setOpen({ source: e.source, eventId: e.eventId })}
                      >
                        {e.eventId}
                      </button>
                    </td>
                    <td>{e.type}</td>
                    <td>{e.status}</td>
                    <td>{text(e.errorCode)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
          </>
        )}
      </Loadable>
      {open && <EventDetailCard source={open.source} eventId={open.eventId} onClose={() => setOpen(null)} />}
    </section>
  );
}
