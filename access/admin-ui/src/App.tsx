import { useEffect, useState } from 'react';
import { ApiProvider } from './api/ApiContext';
import { AuthService } from './auth/authService';
import { Layout } from './components/Layout';
import { GraphPage } from './pages/GraphPage';
import { SyncPage } from './pages/SyncPage';
import { useHashRoute } from './useHashRoute';

type AuthState = 'pending' | 'ready' | 'failed';

/** Корень приложения: завершает вход (или уходит на IdP) и рисует layout с текущим разделом. */
export function App({ auth }: { auth: AuthService }) {
  const [state, setState] = useState<AuthState>('pending');
  const route = useHashRoute();

  useEffect(() => {
    let cancelled = false;
    auth
      .init()
      .then((authenticated) => {
        if (authenticated) {
          if (!cancelled) setState('ready');
          return undefined;
        }
        return auth.login();
      })
      .catch(() => {
        if (!cancelled) setState('failed');
      });
    return () => {
      cancelled = true;
    };
  }, [auth]);

  if (state === 'failed') {
    return <p role="alert">Не удалось выполнить вход. Обновите страницу.</p>;
  }
  if (state === 'pending') {
    return <p>Вход…</p>;
  }
  return (
    <ApiProvider auth={auth}>
      <Layout route={route}>{route === 'graph' ? <GraphPage /> : <SyncPage />}</Layout>
    </ApiProvider>
  );
}
