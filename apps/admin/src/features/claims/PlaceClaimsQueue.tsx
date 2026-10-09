import React, { useEffect, useState, useCallback } from 'react';
import { claimsApi } from '../../core/api/claims';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { Pagination } from '../common/Pagination';
import { ClaimStatusBadge, formatDate } from '../common/Badge';
import { PlaceClaimDecisionModal } from './PlaceClaimDecisionModal';
import { ApiError } from '../../core/api/client';
import type {
  PlaceClaimResponse,
  PlaceClaimStatus,
  ProblemDetail,
} from '../../core/types';

export const PlaceClaimsQueue: React.FC = () => {
  const [claims, setClaims] = useState<PlaceClaimResponse[]>([]);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLast, setIsLast] = useState(true);

  // Filtros
  const [statusFilter, setStatusFilter] = useState<PlaceClaimStatus | ''>('PENDING');

  // Estados de tela
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  // Modal de análise / decisão
  const [selectedClaim, setSelectedClaim] = useState<PlaceClaimResponse | null>(null);

  const fetchClaims = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);

    try {
      const response = await claimsApi.listPlaceClaims({
        page,
        size,
        status: statusFilter || undefined,
      });

      setClaims(response.content);
      setPage(response.pageNumber);
      setTotalPages(response.totalPages);
      setTotalElements(response.totalElements);
      setIsLast(response.isLast);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível carregar a fila de reivindicações de locais.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [page, size, statusFilter]);

  useEffect(() => {
    fetchClaims();
  }, [fetchClaims]);

  const handleStatusChange = (newStatus: PlaceClaimStatus | '') => {
    setStatusFilter(newStatus);
    setPage(0);
  };

  return (
    <div>
      <div className="header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 className="title">Fila de Reivindicações de Locais</h1>
          <p className="subtitle">
            Triagem e decisão administrativa sobre solicitações de empresas para assumir gestão de locais.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={fetchClaims}
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
          <label htmlFor="filter-claim-status" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            Status:
          </label>
          <select
            id="filter-claim-status"
            className="input-field"
            style={{ width: 'auto', padding: '6px 12px' }}
            value={statusFilter}
            onChange={(e) => handleStatusChange(e.target.value as PlaceClaimStatus | '')}
          >
            <option value="PENDING">Pendentes</option>
            <option value="APPROVED">Aprovadas</option>
            <option value="REJECTED">Rejeitadas</option>
            <option value="">Todas</option>
          </select>
        </div>

        <div style={{ marginLeft: 'auto', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          {totalElements} {totalElements === 1 ? 'reivindicação encontrada' : 'reivindicações encontradas'}
        </div>
      </div>

      {/* Loading */}
      {isLoading && <LoadingSpinner message="Carregando reivindicações de locais..." />}

      {/* Erro */}
      {errorMessage && (
        <ErrorMessage
          message={errorMessage}
          problem={problemDetail}
          onRetry={fetchClaims}
        />
      )}

      {/* Vazio */}
      {!isLoading && !errorMessage && claims.length === 0 && (
        <div className="card" style={{ textAlign: 'center', padding: '40px 20px' }}>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.95rem' }}>
            Nenhuma solicitação de reivindicação de local encontrada para os filtros selecionados.
          </p>
        </div>
      )}

      {/* Listagem em Tabela */}
      {!isLoading && !errorMessage && claims.length > 0 && (
        <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
          <table className="table" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border-color)', backgroundColor: 'rgba(255, 255, 255, 0.02)' }}>
                <th style={{ padding: '12px 16px', fontSize: '0.8rem', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                  Conta Comercial
                </th>
                <th style={{ padding: '12px 16px', fontSize: '0.8rem', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                  Documento Fiscal
                </th>
                <th style={{ padding: '12px 16px', fontSize: '0.8rem', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                  Local Solicitado
                </th>
                <th style={{ padding: '12px 16px', fontSize: '0.8rem', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                  Data
                </th>
                <th style={{ padding: '12px 16px', fontSize: '0.8rem', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                  Status
                </th>
                <th style={{ padding: '12px 16px', fontSize: '0.8rem', textTransform: 'uppercase', color: 'var(--text-secondary)', textAlign: 'right' }}>
                  Ações
                </th>
              </tr>
            </thead>
            <tbody>
              {claims.map((claim) => (
                <tr
                  key={claim.id}
                  style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.15s ease' }}
                >
                  <td style={{ padding: '14px 16px', fontWeight: 600 }}>
                    {claim.corporateName}
                  </td>
                  <td style={{ padding: '14px 16px', color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                    {claim.taxId}
                  </td>
                  <td style={{ padding: '14px 16px' }}>
                    <div style={{ fontWeight: 500 }}>{claim.placeName}</div>
                    <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      {claim.city} / {claim.state}
                    </div>
                  </td>
                  <td style={{ padding: '14px 16px', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                    {formatDate(claim.createdAt)}
                  </td>
                  <td style={{ padding: '14px 16px' }}>
                    <ClaimStatusBadge status={claim.status} />
                  </td>
                  <td style={{ padding: '14px 16px', textAlign: 'right' }}>
                    <button
                      type="button"
                      className={`btn ${claim.status === 'PENDING' ? 'btn-primary' : 'btn-secondary'}`}
                      style={{ padding: '6px 12px', fontSize: '0.8rem' }}
                      onClick={() => setSelectedClaim(claim)}
                    >
                      {claim.status === 'PENDING' ? 'Analisar' : 'Ver Detalhes'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Paginação */}
      {!isLoading && !errorMessage && totalPages > 1 && (
        <Pagination
          pageNumber={page}
          totalPages={totalPages}
          totalElements={totalElements}
          isLast={isLast}
          onPageChange={(newPage) => setPage(newPage)}
        />
      )}

      {/* Modal de Detalhes / Decisão */}
      <PlaceClaimDecisionModal
        claim={selectedClaim}
        isOpen={!!selectedClaim}
        onClose={() => setSelectedClaim(null)}
        onDecisionComplete={() => {
          setSelectedClaim(null);
          fetchClaims();
        }}
      />
    </div>
  );
};
