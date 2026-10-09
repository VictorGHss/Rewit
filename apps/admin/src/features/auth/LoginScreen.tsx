import React, { useState } from 'react';
import { authApi } from '../../core/api/auth';
import { ApiError } from '../../core/api/client';
import { ErrorMessage } from '../common/ErrorMessage';
import type { ProblemDetail } from '../../core/types';

interface LoginScreenProps {
  onLoginSuccess: () => void;
}

export const LoginScreen: React.FC<LoginScreenProps> = ({ onLoginSuccess }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);
  const [isAccessForbidden, setIsAccessForbidden] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isLoading) return;

    if (!email.trim() || !password) {
      setErrorMessage('Por favor, informe o e-mail e a senha.');
      return;
    }

    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);
    setIsAccessForbidden(false);

    try {
      await authApi.login({ email: email.trim(), password });

      // Verificação explícita de autorização administrativa via endpoint protegido
      try {
        await authApi.verifyAdminAccess();
        onLoginSuccess();
      } catch (verifyErr) {
        if (verifyErr instanceof ApiError && verifyErr.status === 403) {
          // Usuário autenticado, porém sem permissão MODERATOR ou ADMIN
          setIsAccessForbidden(true);
          return;
        }
        throw verifyErr;
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Ocorreu um erro inesperado ao autenticar.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleClearAndRetry = async () => {
    await authApi.logout();
    setIsAccessForbidden(false);
    setEmail('');
    setPassword('');
    setErrorMessage(null);
  };

  if (isAccessForbidden) {
    return (
      <div
        style={{
          display: 'flex',
          minHeight: '100vh',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'var(--bg-primary)',
          padding: '20px',
        }}
      >
        <div
          className="card"
          role="alert"
          style={{
            maxWidth: '460px',
            width: '100%',
            textAlign: 'center',
            padding: '36px 28px',
            borderTop: '4px solid var(--accent-danger)',
          }}
        >
          <span style={{ fontSize: '3rem', display: 'block', marginBottom: '16px' }}>🚫</span>
          <h2 style={{ fontSize: '1.4rem', color: 'var(--accent-danger)', marginBottom: '12px' }}>
            Acesso Restrito
          </h2>
          <p
            style={{
              color: 'var(--text-secondary)',
              fontSize: '0.95rem',
              lineHeight: '1.5',
              marginBottom: '24px',
            }}
          >
            Sua conta está autenticada, mas não possui permissões administrativas (MODERATOR ou ADMIN)
            para acessar o painel de moderação da rede Rewit.
          </p>
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleClearAndRetry}
            style={{ width: '100%' }}
          >
            Tentar com outra conta
          </button>
        </div>
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'flex',
        minHeight: '100vh',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: 'var(--bg-primary)',
        padding: '20px',
      }}
    >
      <div
        className="card"
        style={{
          maxWidth: '420px',
          width: '100%',
          padding: '36px 32px',
        }}
      >
        <div style={{ textAlign: 'center', marginBottom: '28px' }}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '8px',
              fontSize: '1.6rem',
              fontWeight: 700,
              color: 'var(--accent-primary)',
              marginBottom: '8px',
            }}
          >
            <span>📍</span>
            <span>Rewit Admin</span>
          </div>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Acesso exclusivo para moderadores e administradores
          </p>
        </div>

        {errorMessage && (
          <ErrorMessage message={errorMessage} problem={problemDetail} />
        )}

        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: '18px' }}>
            <label
              htmlFor="email-input"
              style={{
                display: 'block',
                marginBottom: '6px',
                fontSize: '0.85rem',
                color: 'var(--text-secondary)',
                fontWeight: 500,
              }}
            >
              E-mail
            </label>
            <input
              id="email-input"
              type="email"
              autoComplete="email"
              required
              className="input-field"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="moderador@rewit.app"
              disabled={isLoading}
            />
          </div>

          <div style={{ marginBottom: '24px' }}>
            <label
              htmlFor="password-input"
              style={{
                display: 'block',
                marginBottom: '6px',
                fontSize: '0.85rem',
                color: 'var(--text-secondary)',
                fontWeight: 500,
              }}
            >
              Senha
            </label>
            <input
              id="password-input"
              type="password"
              autoComplete="current-password"
              required
              className="input-field"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              disabled={isLoading}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            style={{ width: '100%', padding: '12px' }}
            disabled={isLoading}
          >
            {isLoading ? 'Autenticando...' : 'Entrar no Painel'}
          </button>
        </form>
      </div>
    </div>
  );
};
