import { ReactNode } from 'react';
import { AsyncState } from '../hooks/useAsync';

/** Время в локальной зоне; ISO-значение в подсказке. Пустое значение — прочерк. */
export function Time({ value }: { value: string | null | undefined }) {
  if (!value) return <span>—</span>;
  const date = new Date(value);
  const text = Number.isNaN(date.getTime()) ? value : date.toLocaleString();
  return (
    <time dateTime={value} title={value}>
      {text}
    </time>
  );
}

/** Состояния загрузки, ошибки и пустых данных вокруг содержимого. */
export function Loadable<T>({
  state,
  isEmpty,
  emptyText = 'Нет данных',
  children,
}: {
  state: AsyncState<T>;
  isEmpty?: (data: T) => boolean;
  emptyText?: string;
  children: (data: T) => ReactNode;
}) {
  if (state.status === 'loading') return <p>Загрузка…</p>;
  if (state.status === 'error') return <p role="alert">{state.message}</p>;
  if (isEmpty?.(state.data)) return <p className="empty">{emptyText}</p>;
  return <>{children(state.data)}</>;
}

export function Pager({
  page,
  size,
  total,
  onPage,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="pager">
      <button type="button" disabled={page <= 0} onClick={() => onPage(page - 1)}>
        Назад
      </button>
      <span>
        Страница {page + 1} из {pages} (всего {total})
      </span>
      <button type="button" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Вперёд
      </button>
    </div>
  );
}

export function Badge({ children, kind }: { children: ReactNode; kind?: 'ok' | 'warn' | 'bad' }) {
  return <span className={`badge ${kind ?? ''}`}>{children}</span>;
}

/** Значение произвольного типа как текст (React экранирует его сам). */
export function text(value: unknown): string {
  if (value === null || value === undefined) return '—';
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/** Выпадающий список с пустым значением «все». */
export function Select({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: ReadonlyArray<string>;
  onChange: (value: string) => void;
}) {
  return (
    <label>
      {label}{' '}
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">все</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Значение {@code datetime-local} → ISO-8601 UTC для параметров from/to. */
export function toIso(local: string): string | undefined {
  if (!local) return undefined;
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}
