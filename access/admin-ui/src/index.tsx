import { createRoot } from 'react-dom/client';
import { App } from './App';
import { createOidcAuthService, loadConsoleConfig } from './auth/authService';
import './styles.css';

const root = createRoot(document.getElementById('root') as HTMLElement);

loadConsoleConfig()
  .then((config) => root.render(<App auth={createOidcAuthService(config)} />))
  .catch(() => root.render(<p role="alert">Не удалось загрузить конфигурацию консоли.</p>));
