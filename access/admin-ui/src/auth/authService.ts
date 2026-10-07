import { InMemoryWebStorage, User, UserManager, WebStorageStateStore } from 'oidc-client-ts';

/** Конфигурация OIDC-клиента; приходит из /console-config.json и в бандл не зашивается. */
export interface ConsoleConfig {
  issuer: string;
  clientId: string;
  scope: string;
}

/** Сервис аутентификации, с которым работает UI. Токен живёт только в памяти. */
export interface AuthService {
  /** Завершает вход (обрабатывает redirect-ответ) и возвращает true, если токен есть. */
  init(): Promise<boolean>;
  /** Текущий access token или null; нигде не сохраняется. */
  getAccessToken(): string | null;
  /** Запускает вход по Authorization Code + PKCE (редирект на IdP). */
  login(): Promise<void>;
  logout(): Promise<void>;
}

/** Читает конфигурацию консоли; ошибка загрузки не глушится. */
export async function loadConsoleConfig(fetchFn: typeof fetch = fetch): Promise<ConsoleConfig> {
  const response = await fetchFn('/console-config.json', { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(`console-config.json: HTTP ${response.status}`);
  }
  const body = (await response.json()) as Partial<ConsoleConfig>;
  if (!body.issuer || !body.clientId || !body.scope) {
    throw new Error('console-config.json: нужны issuer, clientId и scope');
  }
  return { issuer: body.issuer, clientId: body.clientId, scope: body.scope };
}

/**
 * OIDC Authorization Code + PKCE через oidc-client-ts.
 * Пользователь (и токены) хранится в InMemoryWebStorage; в sessionStorage остаётся только
 * одноразовое state/code_verifier на время редиректа.
 */
export function createOidcAuthService(config: ConsoleConfig): AuthService {
  const redirectUri = `${window.location.origin}/`;
  const manager = new UserManager({
    authority: config.issuer,
    client_id: config.clientId,
    redirect_uri: redirectUri,
    post_logout_redirect_uri: redirectUri,
    response_type: 'code',
    scope: config.scope,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  });
  let user: User | null = null;

  return {
    async init() {
      const params = new URLSearchParams(window.location.search);
      if (params.has('code') && params.has('state')) {
        // code/state одноразовые: убираем их из адреса в любом случае, иначе перезагрузка повторит ошибку.
        const clean = () =>
          window.history.replaceState({}, document.title, `${window.location.pathname}${window.location.hash}`);
        try {
          user = await manager.signinRedirectCallback();
        } catch {
          // Просроченный/потерянный state: сбрасываем и просим App начать вход заново.
          user = null;
          clean();
          return false;
        }
        clean();
        if (typeof user.state === 'string' && user.state.startsWith('#/')) {
          window.location.hash = user.state;
        }
      } else {
        user = await manager.getUser();
      }
      return user !== null && !user.expired;
    },
    getAccessToken: () => (user && !user.expired ? user.access_token : null),
    login: () => manager.signinRedirect({ state: window.location.hash }),
    logout: () => manager.signoutRedirect(),
  };
}
