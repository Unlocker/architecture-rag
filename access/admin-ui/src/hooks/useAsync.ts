import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/apiClient';

export type AsyncState<T> =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: T };

/** Сообщение для пользователя; тело ответа сервера в текст не попадает. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) return `Ошибка запроса (HTTP ${error.status})`;
  return 'Не удалось выполнить запрос';
}

/**
 * Загружает данные при смене {@code deps}. {@code reload} перезагружает с индикатором загрузки;
 * при {@code refreshMs} данные обновляются по таймеру без сброса экрана.
 */
export function useAsync<T>(
  load: () => Promise<T>,
  deps: ReadonlyArray<unknown>,
  refreshMs?: number,
): { state: AsyncState<T>; reload: () => void } {
  const [state, setState] = useState<AsyncState<T>>({ status: 'loading' });
  const [tick, setTick] = useState(0);
  const silent = useRef(false);

  useEffect(() => {
    let cancelled = false;
    if (!silent.current) setState({ status: 'loading' });
    silent.current = false;
    load().then(
      (data) => {
        if (!cancelled) setState({ status: 'ready', data });
      },
      (error) => {
        if (!cancelled) setState({ status: 'error', message: describeError(error) });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [...deps, tick]);

  useEffect(() => {
    if (!refreshMs) return undefined;
    const timer = setInterval(() => {
      silent.current = true;
      setTick((t) => t + 1);
    }, refreshMs);
    return () => clearInterval(timer);
  }, [refreshMs]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { state, reload };
}
