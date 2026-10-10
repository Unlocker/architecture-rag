import { FormEvent, useRef, useState } from 'react';
import { useApi } from '../../api/ApiContext';
import { ASSET_TYPES } from '../../api/constants';
import { endpoints } from '../../api/endpoints';
import { AsyncState, describeError } from '../../hooks/useAsync';
import { SearchResponse } from '../../api/types';
import { Loadable } from '../../components/common';

/** Поиск активов: строка запроса, фильтр по типам, таблица результатов. */
export function AssetSearch({ onSelect }: { onSelect: (gid: string) => void }) {
  const api = endpoints(useApi());
  const [q, setQ] = useState('');
  const [types, setTypes] = useState<string[]>([]);
  const latest = useRef(0);
  const [state, setState] = useState<AsyncState<SearchResponse> | null>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!q.trim()) return;
    // Ответ устаревшего запроса не должен затирать результат более нового.
    const id = ++latest.current;
    setState({ status: 'loading' });
    api.search(q.trim(), types).then(
      (data) => id === latest.current && setState({ status: 'ready', data }),
      (error) => id === latest.current && setState({ status: 'error', message: describeError(error) }),
    );
  }

  function toggle(type: string) {
    setTypes((current) => (current.includes(type) ? current.filter((t) => t !== type) : [...current, type]));
  }

  return (
    <section aria-label="Поиск">
      <h3>Поиск</h3>
      <form onSubmit={submit}>
        <input
          type="search"
          aria-label="Запрос"
          value={q}
          maxLength={200}
          onChange={(e) => setQ(e.target.value)}
          placeholder="имя, gid или sourceId"
        />
        <button type="submit">Найти</button>
        <fieldset>
          <legend>Типы</legend>
          {ASSET_TYPES.map((t) => (
            <label key={t}>
              <input type="checkbox" checked={types.includes(t)} onChange={() => toggle(t)} /> {t}
            </label>
          ))}
        </fieldset>
      </form>
      {state && (
        <Loadable state={state} isEmpty={(r) => r.items.length === 0} emptyText="Ничего не найдено">
          {(r) => (
            <>
              {r.truncated && <p role="status">Результат обрезан лимитом.</p>}
              <table aria-label="Результаты поиска">
                <thead>
                  <tr>
                    <th>Тип</th>
                    <th>Имя</th>
                    <th>gid</th>
                    <th>Score</th>
                  </tr>
                </thead>
                <tbody>
                  {r.items.map((h) => (
                    <tr key={h.gid}>
                      <td>{h.type}</td>
                      <td>
                        <button type="button" className="link" onClick={() => onSelect(h.gid)}>
                          {h.name}
                        </button>
                      </td>
                      <td>
                        <code>{h.gid}</code>
                      </td>
                      <td>{h.score}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </Loadable>
      )}
    </section>
  );
}
