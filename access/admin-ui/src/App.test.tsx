import { render, screen, waitFor } from '@testing-library/react';
import { App } from './App';
import { AuthService } from './auth/authService';

function fakeAuth(authenticated: boolean): AuthService & { login: jest.Mock } {
  return {
    init: jest.fn().mockResolvedValue(authenticated),
    getAccessToken: () => (authenticated ? 'token' : null),
    login: jest.fn().mockResolvedValue(undefined),
    logout: jest.fn().mockResolvedValue(undefined),
  };
}

afterEach(() => {
  window.location.hash = '';
});

test('renders layout with navigation after login', async () => {
  render(<App auth={fakeAuth(true)} />);

  expect(await screen.findByRole('link', { name: 'Граф' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Синхронизация' })).toBeTruthy();
  expect(screen.getByRole('heading', { level: 2, name: 'Граф' })).toBeTruthy();
});

test('switches section on hash change', async () => {
  render(<App auth={fakeAuth(true)} />);
  await screen.findByRole('link', { name: 'Граф' });

  window.location.hash = '#/sync';

  expect(await screen.findByRole('heading', { level: 2, name: 'Синхронизация' })).toBeTruthy();
});

test('starts login when there is no token', async () => {
  const auth = fakeAuth(false);
  render(<App auth={auth} />);

  await waitFor(() => expect(auth.login).toHaveBeenCalledTimes(1));
  expect(screen.queryByRole('link', { name: 'Граф' })).toBeNull();
});

test('shows error when sign-in fails', async () => {
  const auth = fakeAuth(true);
  (auth.init as jest.Mock).mockRejectedValue(new Error('boom'));
  render(<App auth={auth} />);

  expect(await screen.findByRole('alert')).toBeTruthy();
});
