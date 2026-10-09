import { tokenStorage } from '../storage/tokenStorage';
import type { ProblemDetail, AuthResponse } from '../types';

export class ApiError extends Error {
  public readonly status: number;
  public readonly problem?: ProblemDetail;

  constructor(status: number, problem?: ProblemDetail, message?: string) {
    const errorMsg =
      message ||
      problem?.detail ||
      problem?.title ||
      `Erro na API (${status})`;
    super(errorMsg);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

export interface ExtendedRequestInit extends RequestInit {
  _isRetry?: boolean;
}

type AuthFailureCallback = () => void;
let onAuthFailureCallback: AuthFailureCallback | null = null;

export function setAuthFailureCallback(callback: AuthFailureCallback | null): void {
  onAuthFailureCallback = callback;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

export function getApiBaseUrl(): string {
  return API_BASE_URL;
}

let activeRefreshPromise: Promise<string> | null = null;

async function executeRefreshToken(): Promise<string> {
  const currentRefreshToken = tokenStorage.getRefreshToken();
  if (!currentRefreshToken) {
    tokenStorage.clear();
    onAuthFailureCallback?.();
    throw new ApiError(401, undefined, 'Sessão expirada. Nenhum refresh token disponível.');
  }

  try {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({ refreshToken: currentRefreshToken }),
    });

    if (!response.ok) {
      let problem: ProblemDetail | undefined;
      try {
        problem = await response.json();
      } catch {
        // Ignora falha de parse
      }
      throw new ApiError(response.status, problem, 'Falha ao renovar credencial de acesso.');
    }

    const data: AuthResponse = await response.json();
    tokenStorage.setTokens(data.accessToken, data.refreshToken, data.user);
    return data.accessToken;
  } catch (err) {
    tokenStorage.clear();
    onAuthFailureCallback?.();
    if (err instanceof ApiError) {
      throw err;
    }
    throw new ApiError(401, undefined, 'Falha de comunicação ao renovar sessão.');
  } finally {
    activeRefreshPromise = null;
  }
}

export async function fetchFromApi<T>(endpoint: string, options?: ExtendedRequestInit): Promise<T> {
  const accessToken = tokenStorage.getAccessToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(options?.headers as Record<string, string> | undefined),
  };

  if (accessToken && !headers['Authorization']) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }

  const url = `${API_BASE_URL}${endpoint}`;
  let response: Response;

  try {
    response = await fetch(url, {
      ...options,
      headers,
    });
  } catch {
    throw new ApiError(0, undefined, 'Não foi possível conectar ao servidor. Verifique sua conexão de rede.');
  }

  // Tratamento de renovação em 401
  const isAuthEndpoint = endpoint.startsWith('/auth/login') || endpoint.startsWith('/auth/refresh');
  if (response.status === 401 && !options?._isRetry && !isAuthEndpoint) {
    if (!activeRefreshPromise) {
      activeRefreshPromise = executeRefreshToken();
    }

    const newAccessToken = await activeRefreshPromise;
    const retryHeaders = {
      ...headers,
      Authorization: `Bearer ${newAccessToken}`,
    };

    return await fetchFromApi<T>(endpoint, {
      ...options,
      headers: retryHeaders,
      _isRetry: true,
    });
  }

  if (!response.ok) {
    let problem: ProblemDetail | undefined;
    try {
      problem = await response.json();
    } catch {
      // Resposta sem JSON (ex.: 502/503 ou texto)
    }

    throw new ApiError(response.status, problem);
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  return response.json();
}
