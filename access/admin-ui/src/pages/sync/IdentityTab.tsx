import { useState } from 'react';
import { useApi } from '../../api/ApiContext';
import { endpoints } from '../../api/endpoints';
import { Loadable, Select, Pager, text, Time } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

const PAGE_SIZE = 50;

const STATUSES = ['OPEN', 'RESOLVED'] as const;

function Conflicts() {
  const api = endpoints(useApi());
  const [status, setStatus] = useState('OPEN');
  const [page, setPage] = useState(0);
  const { state } = useAsync(() => api.conflicts({ status, page, size: PAGE_SIZE }), [status, page]);
  return (
    <section aria-label="Конфликты">
      <h3>Конфликты</h3>
      <Select label="Статус конфликтов" value={status} options={STATUSES} allowAll={false} onChange={(v) => { setStatus(v); setPage(0); }} />
      <Loadable state={state} isEmpty={(p) => p.items.length === 0} emptyText="Конфликтов с таким статусом нет">
        {(p) => (
          <>
            <table aria-label="Конфликты источников">
              <thead>
                <tr>
                  <th>gid</th>
                  <th>Свойство</th>
                  <th>Расходящийся источник</th>
                  <th>Значение</th>
                  <th>Мастер</th>
                  <th>Значение</th>
                  <th>Открыт</th>
                </tr>
              </thead>
              <tbody>
                {p.items.map((c) => (
                  <tr key={`${c.gid}|${c.property}|${c.dissentSource}|${c.dissentType}|${c.dissentId}`}>
                    <td>
                      <code>{c.gid}</code>
                    </td>
                    <td>{c.property}</td>
                    <td>{`${c.dissentSource}/${c.dissentType}/${c.dissentId}`}</td>
                    <td>{text(c.dissentValue)}</td>
                    <td>{`${c.masterSource}/${c.masterType}/${c.masterId}`}</td>
                    <td>{text(c.masterValue)}</td>
                    <td>
                      <Time value={c.openedAt} />
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

function Candidates() {
  const api = endpoints(useApi());
  const [status, setStatus] = useState('OPEN');
  const [page, setPage] = useState(0);
  const { state } = useAsync(() => api.candidates({ status, page, size: PAGE_SIZE }), [status, page]);
  return (
    <section aria-label="Кандидаты">
      <h3>Кандидаты</h3>
      <Select label="Статус кандидатов" value={status} options={STATUSES} allowAll={false} onChange={(v) => { setStatus(v); setPage(0); }} />
      <Loadable state={state} isEmpty={(p) => p.items.length === 0} emptyText="Кандидатов с таким статусом нет">
        {(p) => (
          <>
            <table aria-label="Кандидаты identity">
              <thead>
                <tr>
                  <th>Левая запись</th>
                  <th>Правая запись</th>
                  <th>Семейство</th>
                  <th>Совпавшие признаки</th>
                  <th>Score</th>
                </tr>
              </thead>
              <tbody>
                {p.items.map((c) => (
                  <tr key={`${c.leftSource}|${c.leftType}|${c.leftId}|${c.rightSource}|${c.rightType}|${c.rightId}`}>
                    <td>{`${c.leftSource}/${c.leftType}/${c.leftId}`}</td>
                    <td>{`${c.rightSource}/${c.rightType}/${c.rightId}`}</td>
                    <td>{c.labelFamily}</td>
                    <td>{(c.matched ?? []).map((m) => `${m.feature}=${text(m.value)}`).join(', ') || '—'}</td>
                    <td>{c.score}</td>
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

/** Identity: открытые конфликты источников и кандидаты на совпадение. */
export function IdentityTab() {
  return (
    <>
      <Conflicts />
      <Candidates />
    </>
  );
}
