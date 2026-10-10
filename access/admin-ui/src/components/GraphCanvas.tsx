import cytoscape from 'cytoscape';
import { useEffect, useRef } from 'react';
import { DEFAULT_LABEL_COLOR, LABEL_COLORS } from '../api/constants';
import { Neighborhood } from '../api/types';

/**
 * Тонкая обёртка над Cytoscape.js: рисует окрестность, красит узлы по метке, центр выделяет рамкой,
 * клик по узлу сообщает его gid. Названия узлов выводятся как текст на canvas, HTML не интерпретируется.
 */
export function GraphCanvas({
  data,
  onSelect,
}: {
  data: Neighborhood;
  onSelect: (gid: string) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  useEffect(() => {
    if (!container.current) return undefined;
    const cy = cytoscape({
      container: container.current,
      elements: [
        ...data.nodes.map((n) => ({
          data: {
            id: n.gid,
            label: n.name,
            color: LABEL_COLORS[n.type] ?? DEFAULT_LABEL_COLOR,
            center: n.gid === data.center ? 1 : 0,
            closed: n.isCurrent ? 0 : 1,
          },
        })),
        ...data.edges.map((e, i) => ({
          data: { id: `e${i}`, source: e.source, target: e.target, label: e.type },
        })),
      ],
      style: [
        {
          selector: 'node',
          style: {
            label: 'data(label)',
            'background-color': 'data(color)',
            'font-size': 10,
            'border-width': 'data(center)',
            'border-color': '#000',
          },
        },
        { selector: 'node[closed = 1]', style: { opacity: 0.5 } },
        {
          selector: 'edge',
          style: {
            label: 'data(label)',
            'font-size': 8,
            width: 1,
            'curve-style': 'bezier',
            'target-arrow-shape': 'triangle',
          },
        },
      ],
      layout: { name: 'cose' },
    });
    cy.on('tap', 'node', (event) => onSelectRef.current(event.target.id()));
    return () => cy.destroy();
  }, [data]);

  return <div ref={container} className="graph-canvas" data-testid="graph-canvas" />;
}
