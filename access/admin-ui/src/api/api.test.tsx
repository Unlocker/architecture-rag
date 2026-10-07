import { render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { ApiProvider, useApi } from './ApiContext';
import { createApiClient, ForbiddenError, UnauthorizedError } from './apiClient';
import { AuthService } from '../auth/authService';

// jsdom не предоставляет Response, поэтому отвечаем минимальным фейком.
function response(status: number, body?: unknown): Response {
  return { status, ok: status >= 200 && status < 300, json: async () => body } as Response;
}

function client(fetchFn: jest.Mock) {
  const onUnauthorized = jest.fn();
  const onForbidden = jest.fn();
  const api = createApiClient({ getToken: () => 'abc', onUnauthorized, onForbidden, fetchFn });
  return { api, onUnauthorized, onForbidden };
}

test('sends Bearer token and parses JSON', async () => {
  const fetchFn = jest.fn().mockResolvedValue(response(200, { ok: true }));
  const { api } = client(fetchFn);

  await expect(api.get('/api/x')).resolves.toEqual({ ok: true });

  expect(fetchFn.mock.calls[0][1].headers.Authorization).toBe('Bearer abc');
});

test('401 triggers re-login', async () => {
  const { api, onUnauthorized, onForbidden } = client(jest.fn().mockResolvedValue(response(401)));

  await expect(api.get('/api/x')).rejects.toBeInstanceOf(UnauthorizedError);

  expect(onUnauthorized).toHaveBeenCalledTimes(1);
  expect(onForbidden).not.toHaveBeenCalled();
});

test('403 is reported as forbidden, without re-login', async () => {
  const { api, onUnauthorized, onForbidden } = client(jest.fn().mockResolvedValue(response(403)));

  await expect(api.get('/api/x')).rejects.toBeInstanceOf(ForbiddenError);

  expect(onForbidden).toHaveBeenCalledTimes(1);
  expect(onUnauthorized).not.toHaveBeenCalled();
});

function Probe() {
  const api = useApi();
  useEffect(() => {
    api.get('/api/x').catch(() => undefined);
  }, [api]);
  return <p>content</p>;
}

function fakeAuth(): AuthService & { login: jest.Mock } {
  return {
    init: jest.fn(),
    getAccessToken: () => 'abc',
    login: jest.fn().mockResolvedValue(undefined),
    logout: jest.fn(),
  };
}

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test('ApiProvider redirects to login on 401', async () => {
  globalThis.fetch = jest.fn().mockResolvedValue(response(401));
  const auth = fakeAuth();

  render(
    <ApiProvider auth={auth}>
      <Probe />
    </ApiProvider>,
  );

  await waitFor(() => expect(auth.login).toHaveBeenCalledTimes(1));
});

test('ApiProvider shows the 403 screen', async () => {
  globalThis.fetch = jest.fn().mockResolvedValue(response(403));

  render(
    <ApiProvider auth={fakeAuth()}>
      <Probe />
    </ApiProvider>,
  );

  expect(await screen.findByText('Нет прав')).toBeTruthy();
  expect(screen.queryByText('content')).toBeNull();
});

test('parallel 401 start login once', async () => {
  globalThis.fetch = jest.fn().mockResolvedValue(response(401));
  const auth = fakeAuth();
  function Two() {
    const api = useApi();
    useEffect(() => {
      api.get('/a').catch(() => undefined);
      api.get('/b').catch(() => undefined);
    }, [api]);
    return null;
  }

  render(
    <ApiProvider auth={auth}>
      <Two />
    </ApiProvider>,
  );

  await waitFor(() => expect(auth.login).toHaveBeenCalledTimes(1));
  await new Promise((r) => setTimeout(r, 20));
  expect(auth.login).toHaveBeenCalledTimes(1);
});

test('ApiProvider shows error when login fails', async () => {
  globalThis.fetch = jest.fn().mockResolvedValue(response(401));
  const auth = fakeAuth();
  auth.login.mockRejectedValue(new Error('idp down'));

  render(
    <ApiProvider auth={auth}>
      <Probe />
    </ApiProvider>,
  );

  expect(await screen.findByRole('alert')).toBeTruthy();
});
