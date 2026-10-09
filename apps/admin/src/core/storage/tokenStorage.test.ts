import { describe, it, expect, beforeEach } from 'vitest';
import { tokenStorage } from './tokenStorage';

describe('tokenStorage', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it('salva e recupera tokens estritamente em sessionStorage', () => {
    tokenStorage.setTokens('access-token-123', 'refresh-token-456');

    expect(tokenStorage.getAccessToken()).toBe('access-token-123');
    expect(tokenStorage.getRefreshToken()).toBe('refresh-token-456');
    expect(sessionStorage.getItem('rewit_admin_access_token')).toBe('access-token-123');
    expect(sessionStorage.getItem('rewit_admin_refresh_token')).toBe('refresh-token-456');

    // NUNCA deve persistir credenciais ou tokens em localStorage
    expect(localStorage.getItem('rewit_admin_access_token')).toBeNull();
    expect(localStorage.getItem('rewit_admin_refresh_token')).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('salva e recupera dados do usuário autenticado em sessionStorage', () => {
    const user = {
      id: 'user-uuid-1',
      email: 'admin@rewit.app',
      handle: 'admin_rewit',
      displayName: 'Administrador Rewit',
    };

    tokenStorage.setUser(user);
    expect(tokenStorage.getUser()).toEqual(user);
    expect(localStorage.getItem('rewit_admin_user')).toBeNull();
  });

  it('limpa todos os tokens e sessão sem deixar resíduos', () => {
    tokenStorage.setTokens('access-123', 'refresh-456');
    tokenStorage.setUser({
      id: 'user-1',
      email: 'test@rewit.app',
      handle: 'test',
      displayName: 'Test',
    });

    tokenStorage.clearTokens();

    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(tokenStorage.getUser()).toBeNull();
    expect(sessionStorage.getItem('rewit_admin_access_token')).toBeNull();
    expect(sessionStorage.getItem('rewit_admin_refresh_token')).toBeNull();
  });
});
