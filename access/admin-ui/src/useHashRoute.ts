import { useEffect, useState } from 'react';

export type Route = 'graph' | 'sync';

export const ROUTES: ReadonlyArray<{ route: Route; title: string }> = [
  { route: 'graph', title: 'Граф' },
  { route: 'sync', title: 'Синхронизация' },
];

/** Разбирает location.hash (#/graph, #/sync); неизвестное значение ведёт в «Граф». */
export function parseRoute(hash: string): Route {
  const name = hash.replace(/^#\/?/, '');
  return ROUTES.find((r) => r.route === name)?.route ?? 'graph';
}

/** Маршрут как React-состояние, синхронизированное с hash адресной строки. */
export function useHashRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
