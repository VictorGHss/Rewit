import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import { authApi } from './core/api/auth';
import { tokenStorage } from './core/storage/tokenStorage';
import { moderationApi } from './core/api/moderation';

vi.mock('./core/api/auth', () => ({
  authApi: {
    isAuthenticated: vi.fn(),
    login: vi.fn(),
    verifyAdminAccess: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('./core/storage/tokenStorage', () => ({
  tokenStorage: {
    getUser: vi.fn(),
    getAccessToken: vi.fn(),
    getRefreshToken: vi.fn(),
    setTokens: vi.fn(),
    setUser: vi.fn(),
    clear: vi.fn(),
    clearTokens: vi.fn(),
  },
}));

vi.mock('./core/api/moderation', () => ({
  moderationApi: {
    listReviewReports: vi.fn(),
    listDiscussionReports: vi.fn(),
    getReviewContext: vi.fn(),
    getDiscussionContext: vi.fn(),
    moderateReview: vi.fn(),
    moderateDiscussion: vi.fn(),
  },
}));

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('exibe a tela de login quando não estiver autenticado', () => {
    vi.mocked(authApi.isAuthenticated).mockReturnValue(false);

    render(<App />);

    expect(screen.getByRole('button', { name: /entrar no painel/i })).toBeInTheDocument();
  });

  it('exibe o layout administrativo com abas ativas e sem abas de fases não implementadas', async () => {
    vi.mocked(authApi.isAuthenticated).mockReturnValue(true);
    vi.mocked(tokenStorage.getUser).mockReturnValue({
      id: 'admin-1',
      email: 'admin@rewit.app',
      handle: 'admin',
      displayName: 'Administrador Rewit',
    });
    vi.mocked(moderationApi.listReviewReports).mockResolvedValue({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValue({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /dashboard/i })).toBeInTheDocument();
      expect(screen.getByText('Administrador Rewit')).toBeInTheDocument();
      expect(screen.getByText(/diretrizes operacionais/i)).toBeInTheDocument();
    });

    // Abas esperadas
    expect(screen.getByRole('button', { name: /dashboard/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /denúncias de avaliações/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /denúncias de discussões/i })).toBeInTheDocument();

    // Abas de funcionalidades não implementadas DEVEM TER SIDO REMOVIDAS
    expect(screen.queryByText(/locais \(fase 2\)/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/catálogo global/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/empresas & contas/i)).not.toBeInTheDocument();

    // Informações do usuário na sidebar
    expect(screen.getByText('Administrador Rewit')).toBeInTheDocument();
    expect(screen.getByText('admin@rewit.app')).toBeInTheDocument();
  });

  it('alterna para a fila de discussões ao clicar no menu', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.isAuthenticated).mockReturnValue(true);
    vi.mocked(moderationApi.listReviewReports).mockResolvedValue({
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValue({
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<App />);

    await user.click(screen.getByRole('button', { name: /denúncias de discussões/i }));

    await waitFor(() => {
      expect(screen.getByText(/fila de denúncias de discussões/i)).toBeInTheDocument();
    });
  });

  it('realiza logout ao clicar em Sair do Painel', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.isAuthenticated).mockReturnValue(true);
    vi.mocked(moderationApi.listReviewReports).mockResolvedValue({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValue({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });
    vi.mocked(authApi.logout).mockResolvedValue(undefined);

    render(<App />);

    await user.click(screen.getByRole('button', { name: /sair do painel/i }));

    await waitFor(() => {
      expect(authApi.logout).toHaveBeenCalledTimes(1);
      expect(screen.getByRole('button', { name: /entrar no painel/i })).toBeInTheDocument();
    });
  });
});
