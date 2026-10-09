import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginScreen } from './LoginScreen';
import { authApi } from '../../core/api/auth';
import { ApiError } from '../../core/api/client';

vi.mock('../../core/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    verifyAdminAccess: vi.fn(),
    logout: vi.fn(),
  },
}));

describe('LoginScreen', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renderiza os campos de e-mail, senha e botão de login', () => {
    render(<LoginScreen onLoginSuccess={vi.fn()} />);

    expect(screen.getByLabelText(/e-mail/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/senha/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /entrar no painel/i })).toBeInTheDocument();
  });

  it('autentica com sucesso e verifica papel administrativo chamando onLoginSuccess', async () => {
    const user = userEvent.setup();
    const onLoginSuccess = vi.fn();

    vi.mocked(authApi.login).mockResolvedValueOnce({
      accessToken: 'token-123',
      refreshToken: 'refresh-123',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
      user: {
        id: 'user-admin-1',
        email: 'moderator@rewit.app',
        handle: 'moderator',
        displayName: 'Moderador Rewit',
      },
    });
    vi.mocked(authApi.verifyAdminAccess).mockResolvedValueOnce(true);

    render(<LoginScreen onLoginSuccess={onLoginSuccess} />);

    await user.type(screen.getByLabelText(/e-mail/i), 'moderator@rewit.app');
    await user.type(screen.getByLabelText(/senha/i), 'SenhaSegura123!');
    await user.click(screen.getByRole('button', { name: /entrar no painel/i }));

    await waitFor(() => {
      expect(authApi.login).toHaveBeenCalledWith({
        email: 'moderator@rewit.app',
        password: 'SenhaSegura123!',
      });
      expect(authApi.verifyAdminAccess).toHaveBeenCalledTimes(1);
      expect(onLoginSuccess).toHaveBeenCalledTimes(1);
    });
  });

  it('exibe tela de Acesso Restrito quando o usuário autenticado não possui papel administrativo (403)', async () => {
    const user = userEvent.setup();
    const onLoginSuccess = vi.fn();

    vi.mocked(authApi.login).mockResolvedValueOnce({
      accessToken: 'token-user',
      refreshToken: 'refresh-user',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
      user: {
        id: 'user-normal-1',
        email: 'user@rewit.app',
        handle: 'user_regular',
        displayName: 'Usuário Normal',
      },
    });

    vi.mocked(authApi.verifyAdminAccess).mockRejectedValueOnce(
      new ApiError(403, {
        status: 403,
        title: 'Acesso Negado',
        detail: 'Usuário não possui privilégios de moderação.',
      })
    );

    render(<LoginScreen onLoginSuccess={onLoginSuccess} />);

    await user.type(screen.getByLabelText(/e-mail/i), 'user@rewit.app');
    await user.type(screen.getByLabelText(/senha/i), 'Senha123!');
    await user.click(screen.getByRole('button', { name: /entrar no painel/i }));

    await waitFor(() => {
      expect(screen.getByText(/acesso restrito/i)).toBeInTheDocument();
      expect(screen.getByText(/não possui permissões administrativas/i)).toBeInTheDocument();
      expect(onLoginSuccess).not.toHaveBeenCalled();
    });

    // Clicar em "Tentar com outra conta"
    await user.click(screen.getByRole('button', { name: /tentar com outra conta/i }));

    await waitFor(() => {
      expect(authApi.logout).toHaveBeenCalledTimes(1);
      expect(screen.getByLabelText(/e-mail/i)).toBeInTheDocument();
    });
  });

  it('exibe mensagem de erro RFC 7807 em caso de credenciais inválidas', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockRejectedValueOnce(
      new ApiError(401, {
        status: 401,
        title: 'Falha de Autenticação',
        detail: 'E-mail ou senha incorretos.',
      })
    );

    render(<LoginScreen onLoginSuccess={vi.fn()} />);

    await user.type(screen.getByLabelText(/e-mail/i), 'wrong@rewit.app');
    await user.type(screen.getByLabelText(/senha/i), 'errada');
    await user.click(screen.getByRole('button', { name: /entrar no painel/i }));

    await waitFor(() => {
      expect(screen.getByText('E-mail ou senha incorretos.')).toBeInTheDocument();
    });
  });
});
