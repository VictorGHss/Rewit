import React, { useEffect, useState, useCallback } from 'react';
import { moderationApi } from '../../core/api/moderation';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { ApiError } from '../../core/api/client';
import type { ProblemDetail } from '../../core/types';

interface DashboardProps {
  onNavigateToReviews?: () => void;
  onNavigateToDiscussions?: () => void;
}

export const Dashboard: React.FC<DashboardProps> = ({
  onNavigateToReviews,
  onNavigateToDiscussions,
}) => {
  const [pendingReviewsCount, setPendingReviewsCount] = useState<number | null>(null);
  const [pendingDiscussionsCount, setPendingDiscussionsCount] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  const loadMetrics = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);

    try {
      const [reviewsRes, discussionsRes] = await Promise.all([
        moderationApi.listReviewReports({ page: 0, size: 1, status: 'PENDING' }),
        moderationApi.listDiscussionReports({ page: 0, size: 1, status: 'PENDING' }),
      ]);

      setPendingReviewsCount(reviewsRes.totalElements);
      setPendingDiscussionsCount(discussionsRes.totalElements);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível obter as métricas do painel.');
      }
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadMetrics();
  }, [loadMetrics]);

  const totalPending =
    pendingReviewsCount !== null && pendingDiscussionsCount !== null
      ? pendingReviewsCount + pendingDiscussionsCount
      : null;

  return (
    <div>
      <div className="header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 className="title">Painel de Moderação</h1>
          <p className="subtitle">
            Visão consolidada das filas de triagem e denúncias ativas da rede Rewit.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={loadMetrics}
          disabled={isLoading}
        >
          {isLoading ? 'Atualizando...' : '🔄 Atualizar Filas'}
        </button>
      </div>

      {isLoading && <LoadingSpinner message="Consultando filas de moderação..." />}

      {errorMessage && (
        <ErrorMessage
          message={errorMessage}
          problem={problemDetail}
          onRetry={loadMetrics}
        />
      )}

      {!isLoading && !errorMessage && (
        <>
          <div className="stats-grid">
            <div className="stat-card" style={{ borderLeft: '4px solid var(--accent-warning)' }}>
              <div className="stat-label">Total de Casos Pendentes</div>
              <div className="stat-value">{totalPending ?? 0}</div>
              <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginTop: '6px' }}>
                Aguardando análise de moderação
              </div>
            </div>

            <div className="stat-card" style={{ cursor: onNavigateToReviews ? 'pointer' : 'default' }} onClick={onNavigateToReviews}>
              <div className="stat-label">Denúncias de Avaliações</div>
              <div className="stat-value" style={{ color: pendingReviewsCount ? 'var(--accent-warning)' : 'var(--text-primary)' }}>
                {pendingReviewsCount ?? 0}
              </div>
              {onNavigateToReviews && (
                <button
                  type="button"
                  className="btn btn-secondary"
                  style={{ marginTop: '12px', fontSize: '0.8rem', padding: '4px 10px' }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onNavigateToReviews();
                  }}
                >
                  Acessar Fila de Avaliações →
                </button>
              )}
            </div>

            <div className="stat-card" style={{ cursor: onNavigateToDiscussions ? 'pointer' : 'default' }} onClick={onNavigateToDiscussions}>
              <div className="stat-label">Denúncias de Discussões</div>
              <div className="stat-value" style={{ color: pendingDiscussionsCount ? 'var(--accent-warning)' : 'var(--text-primary)' }}>
                {pendingDiscussionsCount ?? 0}
              </div>
              {onNavigateToDiscussions && (
                <button
                  type="button"
                  className="btn btn-secondary"
                  style={{ marginTop: '12px', fontSize: '0.8rem', padding: '4px 10px' }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onNavigateToDiscussions();
                  }}
                >
                  Acessar Fila de Discussões →
                </button>
              )}
            </div>
          </div>

          <div className="card">
            <h3 style={{ marginBottom: '8px' }}>Diretrizes Operacionais de Moderação</h3>
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', lineHeight: '1.5' }}>
              Todas as ações de moderação (remoção ou restauração) são auditadas de forma indelével pelo backend,
              exigindo motivo estruturado e justificativa formal de 15 a 1.000 caracteres. Respeite a privacidade
              dos autores e denunciantes, preservando o anonimato público previsto pelas regras do domínio.
            </p>
          </div>
        </>
      )}
    </div>
  );
};
