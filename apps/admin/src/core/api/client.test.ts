import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fetchFromApi, ApiError, setAuthFailureCallback } from './client';
import { tokenStorage } from '../storage/tokenStorage';

describe('client.ts (fetchFromApi)', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('injeta Authorization Bearer quando accessToken estiver presente no storage', async () => {
    tokenStorage.setTokens('valid-access-token', 'valid-refresh-token');

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ status: 'ok' }),
    });
    globalThis.fetch = mockFetch;

    const result = await fetchFromApi<{ status: string }>('/admin/status');

    expect(result).toEqual({ status: 'ok' });
    expect(mockFetch).toHaveBeenCalledTimes(1);

    const callArgs = mockFetch.mock.calls[0];
    expect(callArgs[0]).toContain('/api/v1/admin/status');
    const headers = callArgs[1].headers;
    expect(headers['Authorization']).toBe('Bearer valid-access-token');
  });

  it('lança ApiError formatado a partir de resposta RFC 7807', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        type: 'https://rewit.app/errors/bad-request',
        title: 'Requisição Inválida',
        status: 400,
        detail: 'O parâmetro de justificativa deve ter no mínimo 15 caracteres.',
      }),
    });
    globalThis.fetch = mockFetch;

    await expect(fetchFromApi('/admin/test')).rejects.toThrow(ApiError);

    try {
      await fetchFromApi('/admin/test');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      const apiErr = err as ApiError;
      expect(apiErr.status).toBe(400);
      expect(apiErr.message).toBe('O parâmetro de justificativa deve ter no mínimo 15 caracteres.');
      expect(apiErr.problem?.title).toBe('Requisição Inválida');
    }
  });

  it('lança erro amigável em caso de falha de conexão de rede', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(fetchFromApi('/admin/test')).rejects.toThrow(
      'Não foi possível conectar ao servidor. Verifique sua conexão de rede.'
    );
  });

  it('realiza refresh de token com sucesso em 401 e retenta requisição original com novo token', async () => {
    tokenStorage.setTokens('expired-token', 'valid-refresh-token');

    let requestCount = 0;
    const mockFetch = vi.fn().mockImplementation(async (url: string, options?: RequestInit) => {
      requestCount++;
      if (url.includes('/admin/reports') && requestCount === 1) {
        // Primeira requisição falha com 401
        return {
          ok: false,
          status: 401,
          json: async () => ({ title: 'Unauthorized', detail: 'Token expirado' }),
        };
      }
      if (url.includes('/auth/refresh')) {
        // Requisição de refresh
        return {
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: 'new-refreshed-token',
            refreshToken: 'new-rotated-refresh-token',
            tokenType: 'Bearer',
            expiresInSeconds: 3600,
          }),
        };
      }
      if (url.includes('/admin/reports') && requestCount === 3) {
        // Retry da requisição original com o novo token
        const headers = options?.headers as Record<string, string> | undefined;
        expect(headers?.['Authorization']).toBe('Bearer new-refreshed-token');
        return {
          ok: true,
          status: 200,
          json: async () => ({ content: [{ id: 'report-1' }] }),
        };
      }
      throw new Error(`Chamada inesperada para ${url}`);
    });

    globalThis.fetch = mockFetch;

    const result = await fetchFromApi<{ content: Array<{ id: string }> }>('/admin/reports');

    expect(result.content).toHaveLength(1);
    expect(tokenStorage.getAccessToken()).toBe('new-refreshed-token');
    expect(tokenStorage.getRefreshToken()).toBe('new-rotated-refresh-token');
  });

  it('evita refreshes concorrentes duplicados ao receber múltiplos 401 simultâneos', async () => {
    tokenStorage.setTokens('expired-token', 'valid-refresh-token');

    let refreshCallCount = 0;
    const mockFetch = vi.fn().mockImplementation(async (url: string, options?: RequestInit) => {
      if (url.includes('/auth/refresh')) {
        refreshCallCount++;
        // Simular latência do endpoint de refresh
        await new Promise((res) => setTimeout(res, 30));
        return {
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: 'new-batch-token',
            refreshToken: 'new-batch-refresh',
            tokenType: 'Bearer',
            expiresInSeconds: 3600,
          }),
        };
      }
      if (url.includes('/admin/test-1') || url.includes('/admin/test-2')) {
        const headers = options?.headers as Record<string, string> | undefined;
        const authHeader = headers?.['Authorization'] || headers?.Authorization;
        if (authHeader !== 'Bearer new-batch-token') {
          return {
            ok: false,
            status: 401,
            json: async () => ({ title: 'Unauthorized' }),
          };
        }
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ success: true }),
      };
    });

    globalThis.fetch = mockFetch;

    // Disparar duas requisições simultâneas
    const [res1, res2] = await Promise.all([
      fetchFromApi<{ success: boolean }>('/admin/test-1'),
      fetchFromApi<{ success: boolean }>('/admin/test-2'),
    ]);

    expect(res1.success).toBe(true);
    expect(res2.success).toBe(true);
    // Deve ter chamado /auth/refresh exatamente UMA vez
    expect(refreshCallCount).toBe(1);
  });

  it('limpa tokens e aciona callback de falha quando o refresh de token falhar com 401', async () => {
    tokenStorage.setTokens('expired-token', 'invalid-refresh-token');

    const authFailureSpy = vi.fn();
    setAuthFailureCallback(authFailureSpy);

    const mockFetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/auth/refresh')) {
        return {
          ok: false,
          status: 401,
          json: async () => ({ title: 'Unauthorized', detail: 'Refresh token revogado' }),
        };
      }
      return {
        ok: false,
        status: 401,
        json: async () => ({ title: 'Unauthorized' }),
      };
    });

    globalThis.fetch = mockFetch;

    await expect(fetchFromApi('/admin/protected')).rejects.toThrow();

    expect(authFailureSpy).toHaveBeenCalledTimes(1);
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
  });
});
