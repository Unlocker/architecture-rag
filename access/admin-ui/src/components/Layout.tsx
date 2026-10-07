import { ReactNode } from 'react';
import { Route, ROUTES } from '../useHashRoute';

/** Каркас: шапка с навигацией по разделам и область содержимого. */
export function Layout({ route, children }: { route: Route; children: ReactNode }) {
  return (
    <div className="layout">
      <header>
        <h1>Arch RAG</h1>
        <nav aria-label="Разделы">
          {ROUTES.map(({ route: r, title }) => (
            <a key={r} href={`#/${r}`} aria-current={r === route ? 'page' : undefined}>
              {title}
            </a>
          ))}
        </nav>
      </header>
      <main>{children}</main>
    </div>
  );
}
