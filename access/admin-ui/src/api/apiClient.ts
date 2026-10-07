/** Ошибка API-вызова с HTTP-статусом (кроме 401/403, которые обрабатываются отдельно). */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 401: токен отсутствует или недействителен. Вход уже перезапущен. */
export class UnauthorizedError extends ApiError {
  constructor() {
    super(401, 'Unauthorized');
    this.name = 'UnauthorizedError';
  }
}

/** 403: токен валиден, но scope не хватает. */
export class ForbiddenError extends ApiError {
  constructor() {
    super(403, 'Forbidden');
    this.name = 'ForbiddenError';
  }
}

export interface ApiClientOptions {
  getToken: () => string | null;
  /** Вызывается на 401: повторный вход. */
  onUnauthorized: () => void;
  /** Вызывается на 403: UI показывает экран «нет прав». */
  onForbidden: () => void;
  fetchFn?: typeof fetch;
}

/** Типизированный клиент: fetch + Bearer-токен, JSON в обе стороны. */
export interface ApiClient {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body?: unknown): Promise<T>;
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const fetchFn = options.fetchFn ?? ((input, init) => fetch(input, init));

  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    const token = options.getToken();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetchFn(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (response.status === 401) {
      options.onUnauthorized();
      throw new UnauthorizedError();
    }
    if (response.status === 403) {
      options.onForbidden();
      throw new ForbiddenError();
    }
    if (!response.ok) {
      throw new ApiError(response.status, `HTTP ${response.status}`);
    }
    return (response.status === 204 ? undefined : await response.json()) as T;
  }

  return {
    get: (path) => request('GET', path),
    post: (path, body) => request('POST', path, body),
  };
}
