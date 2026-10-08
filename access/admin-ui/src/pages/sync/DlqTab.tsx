import { useState } from 'react';
import { useApi } from '../../api/ApiContext';
import { SOURCES } from '../../api/constants';
import { endpoints } from '../../api/endpoints';
import { Loadable, Pager, Select, text, Time } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

const PAGE_SIZE = 50;

/** DLQ / quarantine: записи с кодом и причиной; по умолчанию только открытые. */
export function DlqTab() {
  const api = endpoints(useApi());
  const [source, setSource] = useState('');
  const [all, setAll] = useState(false);
  const [page, setPage] = useState(0);
  const { state } = useAsync(
    () => api.dlq({ source, replayed: all ? undefined : false, page, size: PAGE_SIZE }),
    [source, all, page],
  );
  return (
    <section aria-label="DLQ">
      <div className="filters">
        <Select
          label="Источник"
          value={source}
          options={SOURCES}
          onChange={(v) => {
            setSource(v);
            setPage(0);
          }}
        />
        <label>
          <input
            type="checkbox"
            checked={all}
            onChange={(e) => {
              setAll(e.target.checked);
              setPage(0);
            }}
          />{' '}
          Показать повторённые
        </label>
      </div>
      <Loadable state={state} isEmpty={(p) => p.items.length === 0} emptyText="Записей DLQ нет">
        {(p) => (
          <>
            <table aria-label="Записи DLQ">
              <thead>
                <tr>
                  <th>Создана</th>
                  <th>Источник</th>
                  <th>eventId</th>
                  <th>Код</th>
                  <th>Причина</th>
                  <th>Повтор</th>
                </tr>
              </thead>
              <tbody>
                {p.items.map((d) => (
                  <tr key={d.id}>
                    <td>
                      <Time value={d.createdAt} />
                    </td>
                    <td>{d.source}</td>
                    <td>{d.eventId}</td>
                    <td>{text(d.errorCode)}</td>
                    <td>{text(d.reason)}</td>
                    <td>
                      <Time value={d.replayedAt} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
          </>
        )}
      </Loadable>
    </section>
  );
}
