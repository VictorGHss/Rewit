const ACCESS_TOKEN_KEY = 'rewit_admin_access_token';
const REFRESH_TOKEN_KEY = 'rewit_admin_refresh_token';
const USER_KEY = 'rewit_admin_user';

export interface StoredUser {
  id: string;
  email: string;
  handle: string;
  displayName: string;
}

/**
 * Gerencia tokens administrativos estritamente em sessionStorage.
 * Nunca persiste em localStorage e nunca registra tokens em logs.
 */
export const tokenStorage = {
  getAccessToken(): string | null {
    try {
      return sessionStorage.getItem(ACCESS_TOKEN_KEY);
    } catch {
      return null;
    }
  },

  getRefreshToken(): string | null {
    try {
      return sessionStorage.getItem(REFRESH_TOKEN_KEY);
    } catch {
      return null;
    }
  },

  setTokens(accessToken: string, refreshToken: string, user?: StoredUser): void {
    try {
      sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
      sessionStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
      if (user) {
        sessionStorage.setItem(USER_KEY, JSON.stringify(user));
      }
    } catch {
      // Falha silenciosa em ambientes restritos
    }
  },

  setUser(user: StoredUser): void {
    try {
      sessionStorage.setItem(USER_KEY, JSON.stringify(user));
    } catch {
      // Falha silenciosa
    }
  },

  getUser(): StoredUser | null {
    try {
      const data = sessionStorage.getItem(USER_KEY);
      return data ? JSON.parse(data) : null;
    } catch {
      return null;
    }
  },

  clear(): void {
    try {
      sessionStorage.removeItem(ACCESS_TOKEN_KEY);
      sessionStorage.removeItem(REFRESH_TOKEN_KEY);
      sessionStorage.removeItem(USER_KEY);
    } catch {
      // Falha silenciosa
    }
  },

  clearTokens(): void {
    this.clear();
  },
};
