import { ReactNode } from 'react';
import { ApiContext } from '../api/ApiContext';
import { ApiClient } from '../api/apiClient';

export type Handler = (path: string) => unknown | Promise<unknown>;

/** API-мок: обработчик выбирается по самому длинному совпавшему префиксу пути. */
export function mockApi(handlers: Record<string, Handler>): ApiClient & { get: jest.Mock } {
  const get = jest.fn(async (path: string) => {
    const key = Object.keys(handlers)
      .filter((k) => path.startsWith(k))
      .sort((a, b) => b.length - a.length)[0];
    if (!key) throw new Error(`unexpected GET ${path}`);
    return handlers[key](path);
  });
  return { get, post: jest.fn() } as unknown as ApiClient & { get: jest.Mock };
}

export function withApi(api: ApiClient, children: ReactNode) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

export function page<T>(items: T[], extra: { page?: number; size?: number; total?: number } = {}) {
  return { items, page: extra.page ?? 0, size: extra.size ?? 50, total: extra.total ?? items.length };
}
