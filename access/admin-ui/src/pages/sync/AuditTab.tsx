import { useState } from 'react';
import { useApi } from '../../api/ApiContext';
import { endpoints } from '../../api/endpoints';
import { Loadable, Pager, text, Time } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

const PAGE_SIZE = 50;

/** Аудит админ-операций ingestion (только чтение). */
export function AuditTab() {
  const api = endpoints(useApi());
  const [page, setPage] = useState(0);
  const { state } = useAsync(() => api.audit({ page, size: PAGE_SIZE }), [page]);
  return (
    <section aria-label="Аудит">
      <Loadable state={state} isEmpty={(p) => p.items.length === 0} emptyText="Операций нет">
        {(p) => (
          <>
            <table aria-label="Аудит админ-операций">
              <thead>
                <tr>
                  <th>Начало</th>
                  <th>Операция</th>
                  <th>Инициатор</th>
                  <th>Статус</th>
                  <th>Ошибка</th>
                </tr>
              </thead>
              <tbody>
                {p.items.map((a) => (
                  <tr key={a.id}>
                    <td>
                      <Time value={a.startedAt} />
                    </td>
                    <td>{a.operation}</td>
                    <td>{text(a.actor)}</td>
                    <td>{a.status}</td>
                    <td>{text(a.error)}</td>
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
