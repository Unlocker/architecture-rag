import { createOidcAuthService, loadConsoleConfig } from './authService';

const mockManager = {
  signinRedirectCallback: jest.fn(),
  getUser: jest.fn(),
  signinRedirect: jest.fn(),
  signoutRedirect: jest.fn(),
};

jest.mock('oidc-client-ts', () => ({
  UserManager: jest.fn(() => mockManager),
  WebStorageStateStore: jest.fn(),
  InMemoryWebStorage: jest.fn(),
}));

const config = { issuer: 'https://idp', clientId: 'c', scope: 'openid architecture.admin' };

function fetchReturning(status: number, body?: unknown): typeof fetch {
  return jest.fn().mockResolvedValue({ status, ok: status < 300, json: async () => body }) as unknown as typeof fetch;
}

describe('loadConsoleConfig', () => {
  test('returns valid config', async () => {
    await expect(loadConsoleConfig(fetchReturning(200, config))).resolves.toEqual(config);
  });

  test('rejects incomplete config', async () => {
    await expect(loadConsoleConfig(fetchReturning(200, { issuer: 'x' }))).rejects.toThrow('issuer, clientId');
  });

  test('rejects HTTP error', async () => {
    await expect(loadConsoleConfig(fetchReturning(500))).rejects.toThrow('HTTP 500');
  });
});

describe('createOidcAuthService', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.history.replaceState({}, '', '/');
  });

  test('init without code reads the in-memory user', async () => {
    mockManager.getUser.mockResolvedValue({ expired: false, access_token: 't' });
    const auth = createOidcAuthService(config);

    await expect(auth.init()).resolves.toBe(true);

    expect(mockManager.signinRedirectCallback).not.toHaveBeenCalled();
    expect(auth.getAccessToken()).toBe('t');
  });

  test('init without user returns false and no token', async () => {
    mockManager.getUser.mockResolvedValue(null);
    const auth = createOidcAuthService(config);

    await expect(auth.init()).resolves.toBe(false);
    expect(auth.getAccessToken()).toBeNull();
  });

  test('callback with stale state clears code/state and asks for a new login', async () => {
    window.history.replaceState({}, '', '/?code=abc&state=old#/sync');
    mockManager.signinRedirectCallback.mockRejectedValue(new Error('No matching state'));
    const auth = createOidcAuthService(config);

    await expect(auth.init()).resolves.toBe(false);

    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('#/sync');
  });

  test('successful callback restores hash route', async () => {
    window.history.replaceState({}, '', '/?code=abc&state=s');
    mockManager.signinRedirectCallback.mockResolvedValue({ expired: false, access_token: 't', state: '#/sync' });
    const auth = createOidcAuthService(config);

    await expect(auth.init()).resolves.toBe(true);

    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('#/sync');
  });
});
