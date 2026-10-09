import React, { useEffect, useState, useCallback } from 'react';
import { moderationApi } from '../../core/api/moderation';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { Pagination } from '../common/Pagination';
import { StatusBadge, ReasonBadge, formatDate } from '../common/Badge';
import { ReviewContextModal } from './ReviewContextModal';
import { ApiError } from '../../core/api/client';
import type {
  AdminReportResponse,
  ReportStatus,
  ReportReason,
  ProblemDetail,
} from '../../core/types';

export const ReviewReportsQueue: React.FC = () => {
  const [reports, setReports] = useState<AdminReportResponse[]>([]);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLast, setIsLast] = useState(true);

  // Filtros
  const [statusFilter, setStatusFilter] = useState<ReportStatus | ''>('PENDING');
  const [reasonFilter, setReasonFilter] = useState<ReportReason | ''>('');

  // Estados de tela
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  // Modal de contexto
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null);

  const fetchReports = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);

    try {
      const response = await moderationApi.listReviewReports({
        page,
        size,
        status: statusFilter || undefined,
        reason: reasonFilter || undefined,
        sort: 'asc',
      });

      setReports(response.content);
      setPage(response.pageNumber);
      setTotalPages(response.totalPages);
      setTotalElements(response.totalElements);
      setIsLast(response.isLast);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível carregar a fila de denúncias de avaliações.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [page, size, statusFilter, reasonFilter]);

  useEffect(() => {
    fetchReports();
  }, [fetchReports]);

  const handleStatusChange = (newStatus: ReportStatus | '') => {
    setStatusFilter(newStatus);
    setPage(0);
  };

  const handleReasonChange = (newReason: ReportReason | '') => {
    setReasonFilter(newReason);
    setPage(0);
  };

  return (
    <div>
      <div className="header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 className="title">Fila de Denúncias de Avaliações</h1>
          <p className="subtitle">
            Triagem administrativa de avaliações reportadas pela comunidade da rede Rewit.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={fetchReports}
          disabled={isLoading}
        >
          {isLoading ? 'Atualizando...' : '🔄 Atualizar'}
        </button>
      </div>

      {/* Barra de Filtros */}
      <div
        className="card"
        style={{
          display: 'flex',
          gap: '16px',
          alignItems: 'center',
          flexWrap: 'wrap',
          padding: '16px 20px',
          marginBottom: '20px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <label htmlFor="filter-status-select" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Status:
          </label>
          <select
            id="filter-status-select"
            className="input-field"
            style={{ width: 'auto', padding: '6px 12px' }}
            value={statusFilter}
            onChange={(e) => handleStatusChange(e.target.value as ReportStatus | '')}
            disabled={isLoading}
          >
            <option value="">Todos os status</option>
            <option value="PENDING">Pendentes (PENDING)</option>
            <option value="ACCEPTED">Aceitas (ACCEPTED)</option>
            <option value="REJECTED">Rejeitadas (REJECTED)</option>
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <label htmlFor="filter-reason-select" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Motivo:
          </label>
          <select
            id="filter-reason-select"
            className="input-field"
            style={{ width: 'auto', padding: '6px 12px' }}
            value={reasonFilter}
            onChange={(e) => handleReasonChange(e.target.value as ReportReason | '')}
            disabled={isLoading}
          >
            <option value="">Todos os motivos</option>
            <option value="SPAM">Spam</option>
            <option value="HARASSMENT">Assédio</option>
            <option value="HATE_SPEECH">Discurso de Ódio</option>
            <option value="MISINFORMATION">Desinformação</option>
            <option value="INAPPROPRIATE_CONTENT">Conteúdo Inapropriado</option>
            <option value="FRAUD">Fraude</option>
          </select>
        </div>
      </div>

      {/* Estados Visuais */}
      {isLoading && <LoadingSpinner message="Carregando denúncias de avaliações..." />}

      {errorMessage && (
        <ErrorMessage
          message={errorMessage}
          problem={problemDetail}
          onRetry={fetchReports}
        />
      )}

      {!isLoading && !errorMessage && reports.length === 0 && (
        <div className="card" style={{ textAlign: 'center', padding: '48px 24px' }}>
          <span style={{ fontSize: '2.5rem', display: 'block', marginBottom: '12px' }}>✓</span>
          <h3 style={{ marginBottom: '6px' }}>Nenhuma denúncia encontrada</h3>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Não há registros com os filtros selecionados na fila de avaliações.
          </p>
        </div>
      )}

      {/* Lista de Denúncias */}
      {!isLoading && !errorMessage && reports.length > 0 && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '0.9rem' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', backgroundColor: 'rgba(255, 255, 255, 0.02)' }}>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Motivo</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Detalhamento</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Data</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>ID Avaliação</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'right' }}>Ação</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((report) => (
                  <tr
                    key={report.id}
                    style={{
                      borderBottom: '1px solid var(--border-color)',
                      transition: 'background-color 0.15s ease',
                    }}
                  >
                    <td style={{ padding: '14px 18px' }}>
                      <ReasonBadge reason={report.reason} />
                    </td>
                    <td style={{ padding: '14px 18px', maxWidth: '320px' }}>
                      <span
                        style={{
                          display: '-webkit-box',
                          WebkitLineClamp: 2,
                          WebkitBoxOrient: 'vertical',
                          overflow: 'hidden',
                          color: report.detail ? 'var(--text-primary)' : 'var(--text-secondary)',
                        }}
                      >
                        {report.detail || 'Sem detalhe fornecido'}
                      </span>
                    </td>
                    <td style={{ padding: '14px 18px' }}>
                      <StatusBadge status={report.status} />
                    </td>
                    <td style={{ padding: '14px 18px', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                      {formatDate(report.createdAt)}
                    </td>
                    <td style={{ padding: '14px 18px', fontFamily: 'monospace', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      {report.reviewId.slice(0, 8)}...
                    </td>
                    <td style={{ padding: '14px 18px', textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button
                        type="button"
                        className="btn btn-primary"
                        style={{ fontSize: '0.8rem', padding: '6px 12px' }}
                        onClick={() => setSelectedReviewId(report.reviewId)}
                      >
                        Analisar Contexto →
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ padding: '0 20px' }}>
            <Pagination
              pageNumber={page}
              totalPages={totalPages}
              totalElements={totalElements}
              isLast={isLast}
              onPageChange={setPage}
              isLoading={isLoading}
            />
          </div>
        </div>
      )}

      {/* Modal de Contexto */}
      {selectedReviewId && (
        <ReviewContextModal
          reviewId={selectedReviewId}
          isOpen={!!selectedReviewId}
          onClose={() => setSelectedReviewId(null)}
          onModerationComplete={() => {
            fetchReports();
          }}
        />
      )}
    </div>
  );
};
