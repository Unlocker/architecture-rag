import { createContext, ReactNode, useContext, useMemo, useState } from 'react';
import { ApiClient, createApiClient } from './apiClient';
import { AuthService } from '../auth/authService';
import { ForbiddenScreen } from '../components/ForbiddenScreen';

const ApiContext = createContext<ApiClient | null>(null);

/**
 * Даёт дочерним компонентам API-клиент. На 401 перезапускает вход,
 * на 403 заменяет содержимое экраном «нет прав».
 */
export function ApiProvider({ auth, children }: { auth: AuthService; children: ReactNode }) {
  const [forbidden, setForbidden] = useState(false);
  const client = useMemo(
    () =>
      createApiClient({
        getToken: () => auth.getAccessToken(),
        onUnauthorized: () => void auth.login(),
        onForbidden: () => setForbidden(true),
      }),
    [auth],
  );
  return <ApiContext.Provider value={client}>{forbidden ? <ForbiddenScreen /> : children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) {
    throw new Error('useApi вызван вне ApiProvider');
  }
  return client;
}
