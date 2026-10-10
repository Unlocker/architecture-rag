import { createContext, ReactNode, useContext, useMemo, useRef, useState } from 'react';
import { ApiClient, createApiClient } from './apiClient';
import { AuthService } from '../auth/authService';
import { ForbiddenScreen } from '../components/ForbiddenScreen';

export const ApiContext = createContext<ApiClient | null>(null);

/**
 * Даёт дочерним компонентам API-клиент. На 401 перезапускает вход,
 * на 403 заменяет содержимое экраном «нет прав».
 */
export function ApiProvider({ auth, children }: { auth: AuthService; children: ReactNode }) {
  const [forbidden, setForbidden] = useState(false);
  const [loginFailed, setLoginFailed] = useState(false);
  const loginStarted = useRef(false);
  const client = useMemo(
    () =>
      createApiClient({
        getToken: () => auth.getAccessToken(),
        onUnauthorized: () => {
          // Параллельные 401 запускают вход один раз.
          if (loginStarted.current) return;
          loginStarted.current = true;
          auth.login().catch(() => {
            loginStarted.current = false;
            setLoginFailed(true);
          });
        },
        onForbidden: () => setForbidden(true),
      }),
    [auth],
  );
  if (loginFailed) {
    return <p role="alert">Не удалось выполнить вход. Обновите страницу.</p>;
  }
  return <ApiContext.Provider value={client}>{forbidden ? <ForbiddenScreen /> : children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) {
    throw new Error('useApi вызван вне ApiProvider');
  }
  return client;
}
