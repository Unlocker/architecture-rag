import { endpoints } from '../../api/endpoints';
import { useApi } from '../../api/ApiContext';
import { Loadable, Time } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

/** Сводка: узлы по меткам, связи по типам, время последнего обновления. */
export function GraphSummary() {
  const api = endpoints(useApi());
  const { state, reload } = useAsync(() => api.stats(), []);
  return (
    <section aria-label="Сводка">
      <h3>Сводка</h3>
      <button type="button" onClick={reload}>
        Обновить
      </button>
      <Loadable state={state} isEmpty={(s) => s.nodes.length === 0 && s.relations.length === 0}>
        {(s) => (
          <>
            <p>
              Последнее обновление: <Time value={s.lastUpdatedAt} />
            </p>
            <div className="columns">
              <table aria-label="Узлы по меткам">
                <thead>
                  <tr>
                    <th>Метка</th>
                    <th>Узлов</th>
                  </tr>
                </thead>
                <tbody>
                  {s.nodes.map((c) => (
                    <tr key={c.name}>
                      <td>{c.name}</td>
                      <td>{c.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <table aria-label="Связи по типам">
                <thead>
                  <tr>
                    <th>Тип связи</th>
                    <th>Связей</th>
                  </tr>
                </thead>
                <tbody>
                  {s.relations.map((c) => (
                    <tr key={c.name}>
                      <td>{c.name}</td>
                      <td>{c.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Loadable>
    </section>
  );
}
