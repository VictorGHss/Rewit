import { fetchFromApi } from './client';
import { tokenStorage, type StoredUser } from '../storage/tokenStorage';
import type { LoginRequest, AuthResponse, LogoutRequest } from '../types';

export const authApi = {
  /**
   * Autentica com credenciais locais via POST /api/v1/auth/login.
   * Armazena tokens unicamente em sessionStorage após sucesso.
   */
  async login(credentials: LoginRequest): Promise<AuthResponse> {
    const data = await fetchFromApi<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    });

    tokenStorage.setTokens(data.accessToken, data.refreshToken, data.user);
    return data;
  },

  /**
   * Valida autorização de moderação diretamente na API através de endpoint protegido.
   * Não presume permissões a partir do JWT — a API é a autoridade máxima.
   * Se responder 403, o chamador deve bloquear a exibição do painel.
   */
  async verifyAdminAccess(): Promise<boolean> {
    await fetchFromApi('/admin/reports?page=0&size=1');
    return true;
  },

  /**
   * Encerra a sessão via POST /api/v1/auth/logout.
   * Limpa a sessão local mesmo em caso de erro na rede ou no servidor.
   */
  async logout(): Promise<void> {
    const refreshToken = tokenStorage.getRefreshToken();
    try {
      if (refreshToken) {
        const payload: LogoutRequest = { refreshToken };
        await fetchFromApi<void>('/auth/logout', {
          method: 'POST',
          body: JSON.stringify(payload),
        });
      }
    } catch {
      // Falha na requisição de logout não bloqueia a limpeza local
    } finally {
      tokenStorage.clear();
    }
  },

  getCurrentUser(): StoredUser | null {
    return tokenStorage.getUser();
  },

  isAuthenticated(): boolean {
    return !!tokenStorage.getAccessToken();
  },
};
