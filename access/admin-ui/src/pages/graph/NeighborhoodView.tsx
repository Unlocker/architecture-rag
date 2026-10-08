import { useState } from 'react';
import { useApi } from '../../api/ApiContext';
import { RELATION_TYPES } from '../../api/constants';
import { endpoints } from '../../api/endpoints';
import { GraphCanvas } from '../../components/GraphCanvas';
import { Loadable } from '../../components/common';
import { useAsync } from '../../hooks/useAsync';

/** Окрестность узла: глубина 1 или 2, фильтр типов связей, баннер при {@code truncated}. */
export function NeighborhoodView({ gid, onSelect }: { gid: string; onSelect: (gid: string) => void }) {
  const api = endpoints(useApi());
  const [depth, setDepth] = useState(1);
  const [relTypes, setRelTypes] = useState<string[]>([]);
  const { state } = useAsync(() => api.neighborhood(gid, depth, relTypes), [gid, depth, relTypes]);

  function toggle(type: string) {
    setRelTypes((cur) => (cur.includes(type) ? cur.filter((t) => t !== type) : [...cur, type]));
  }

  return (
    <section aria-label="Окрестность">
      <h3>Окрестность</h3>
      <label>
        Глубина{' '}
        <select value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
          <option value={1}>1</option>
          <option value={2}>2</option>
        </select>
      </label>
      <fieldset>
        <legend>Типы связей (пусто — все)</legend>
        {RELATION_TYPES.map((t) => (
          <label key={t}>
            <input type="checkbox" checked={relTypes.includes(t)} onChange={() => toggle(t)} /> {t}
          </label>
        ))}
      </fieldset>
      <Loadable state={state}>
        {(n) => (
          <>
            {n.truncated && (
              <p role="status" className="banner">
                Окрестность показана не полностью: превышен бюджет узлов или связей.
              </p>
            )}
            <GraphCanvas data={n} onSelect={onSelect} />
          </>
        )}
      </Loadable>
    </section>
  );
}
