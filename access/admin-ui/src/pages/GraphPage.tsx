import { useState } from 'react';
import { AssetCard } from './graph/AssetCard';
import { AssetSearch } from './graph/AssetSearch';
import { GraphSummary } from './graph/GraphSummary';
import { NeighborhoodView } from './graph/NeighborhoodView';

/** Раздел «Граф»: сводка, поиск, карточка выбранного актива и его окрестность. */
export function GraphPage() {
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <section>
      <h2>Граф</h2>
      <GraphSummary />
      <AssetSearch onSelect={setSelected} />
      {selected && (
        <>
          <AssetCard gid={selected} />
          <NeighborhoodView gid={selected} onSelect={setSelected} />
        </>
      )}
    </section>
  );
}
