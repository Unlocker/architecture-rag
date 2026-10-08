import { useApi } from '../../api/ApiContext';
import { STALE_AFTER_MS } from '../../api/constants';
import { endpoints } from '../../api/endpoints';
import { NodeCard } from '../../api/types';
import { Badge, Loadable, text, Time } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

/** Актив считается устаревшим, если он текущий, но давно не виден источникам. */
export function isStale(card: NodeCard, now: number = Date.now()): boolean {
  if (!card.isCurrent || !card.lastSeenAt) return false;
  const seen = new Date(card.lastSeenAt).getTime();
  return !Number.isNaN(seen) && now - seen > STALE_AFTER_MS;
}

/** Карточка актива: свойства, жизненный цикл и provenance (записи источников). */
export function AssetCard({ gid }: { gid: string }) {
  const api = endpoints(useApi());
  const { state } = useAsync(() => api.node(gid), [gid]);
  return (
    <section aria-label="Карточка актива">
      <h3>Карточка актива</h3>
      <Loadable state={state}>
        {(card) => (
          <>
            <p>
              <strong>{card.type}</strong> <code>{card.gid}</code>
            </p>
            <h4>Свойства</h4>
            <table aria-label="Свойства">
              <tbody>
                {Object.entries(card.properties).map(([k, v]) => (
                  <tr key={k}>
                    <th>{k}</th>
                    <td>{text(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <h4>Жизненный цикл</h4>
            <ul aria-label="Жизненный цикл">
              <li>
                isCurrent: {String(card.isCurrent)}{' '}
                {!card.isCurrent && <Badge kind="bad">закрыт</Badge>}
                {isStale(card) && <Badge kind="warn">устарел</Badge>}
              </li>
              <li>
                firstSeenAt: <Time value={card.firstSeenAt} />
              </li>
              <li>
                lastSeenAt: <Time value={card.lastSeenAt} />
              </li>
              <li>
                deletedAt: <Time value={card.deletedAt} />
              </li>
            </ul>
            <h4>Происхождение</h4>
            <Loadable
              state={{ status: 'ready', data: card.sources }}
              isEmpty={(s) => s.length === 0}
              emptyText="Записей источников нет"
            >
              {(sources) => (
                <table aria-label="Записи источников">
                  <thead>
                    <tr>
                      <th>Источник</th>
                      <th>Версия</th>
                      <th>fetchedAt</th>
                      <th>Авторитетность</th>
                      <th>Статус</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sources.map((s) => (
                      <tr key={`${s.source}|${s.sourceType}|${s.sourceId}`}>
                        <td>
                          {s.source} / {s.sourceType} / {s.sourceId}
                        </td>
                        <td>{text(s.sourceVersion)}</td>
                        <td>
                          <Time value={s.fetchedAt} />
                        </td>
                        <td>{text(s.authority)}</td>
                        <td>
                          {s.active === false || s.deletedAt ? (
                            <Badge kind="bad">tombstone</Badge>
                          ) : (
                            <Badge kind="ok">активна</Badge>
                          )}
                          {s.conflicts && s.conflicts.length > 0 && (
                            <Badge kind="warn">расхождения: {s.conflicts.join(', ')}</Badge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Loadable>
          </>
        )}
      </Loadable>
    </section>
  );
}
