import React, { useState, useEffect, useCallback } from 'react';
import { businessResponsesApi } from '../../core/api/businessResponses';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { Pagination } from '../common/Pagination';
import { formatDate } from '../common/Badge';
import { RestoreBusinessResponseModal } from './RestoreBusinessResponseModal';
import { ApiError } from '../../core/api/client';
import type {
  AdminRemovedBusinessResponse,
  BusinessResponseRemovalSource,
  BusinessResponseRestorationBlocker,
  ProblemDetail,
} from '../../core/types';

const RESTORATION_BLOCKER_EXPLANATIONS: Record<BusinessResponseRestorationBlocker, string> = {
  REMOVED_BY_BUSINESS:
    'Removida pela própria empresa (somente remoções realizadas pela moderação podem ser restauradas).',
  OTHER_ACTIVE_RESPONSE: 'A empresa já possui outra resposta ativa para este mesmo alvo.',
  ACCOUNT_NOT_APPROVED: 'A conta comercial não está aprovada.',
  ADMINISTRATOR_NOT_OPERATIONAL: 'O administrador da conta comercial não está operacional.',
  PLACE_NOT_REPRESENTED: 'O local não está ativo ou não está vinculado à conta comercial.',
};

const GENERIC_UNRESTORABLE_EXPLANATION = 'Esta resposta não atende aos critérios para restauração administrativa.';

/** Sem `restorationBlockers` (API anterior à decisão B), deduz os dois motivos que o payload já trazia. */
const restorationBlockersOf = (item: AdminRemovedBusinessResponse): BusinessResponseRestorationBlocker[] => {
  if (item.restorationBlockers) {
    return item.restorationBlockers;
  }
  const blockers: BusinessResponseRestorationBlocker[] = [];
  if (item.removedBy === 'BUSINESS') {
    blockers.push('REMOVED_BY_BUSINESS');
  }
  if (item.otherActiveResponse) {
    blockers.push('OTHER_ACTIVE_RESPONSE');
  }
  return blockers;
};

/** Uma explicação por motivo, na ordem recebida; motivo desconhecido cai na explicação genérica. */
const getUnrestorableExplanations = (item: AdminRemovedBusinessResponse): string[] => {
  const explanations = restorationBlockersOf(item).map(
    (blocker) => RESTORATION_BLOCKER_EXPLANATIONS[blocker] ?? GENERIC_UNRESTORABLE_EXPLANATION
  );
  const unique = Array.from(new Set(explanations));
  return unique.length > 0 ? unique : [GENERIC_UNRESTORABLE_EXPLANATION];
};

export const RemovedBusinessResponsesQueue: React.FC = () => {
  const [responses, setResponses] = useState<AdminRemovedBusinessResponse[]>([]);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLast, setIsLast] = useState(true);

  // Filtros aplicados
  const [appliedFilters, setAppliedFilters] = useState<{
    removedBy: BusinessResponseRemovalSource | '';
    reviewId: string;
    discussionId: string;
    businessAccountId: string;
    placeId: string;
  }>({
    removedBy: '',
    reviewId: '',
    discussionId: '',
    businessAccountId: '',
    placeId: '',
  });

  // Campos do formulário de filtro (buffer de edição)
  const [filterRemovedBy, setFilterRemovedBy] = useState<BusinessResponseRemovalSource | ''>('');
  const [filterReviewId, setFilterReviewId] = useState('');
  const [filterDiscussionId, setFilterDiscussionId] = useState('');
  const [filterBusinessAccountId, setFilterBusinessAccountId] = useState('');
  const [filterPlaceId, setFilterPlaceId] = useState('');

  // Estados visuais
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);
  const [feedbackSuccess, setFeedbackSuccess] = useState<string | null>(null);

  // Modal de restauração
  const [selectedResponseForRestore, setSelectedResponseForRestore] =
    useState<AdminRemovedBusinessResponse | null>(null);

  const fetchResponses = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);

    try {
      const response = await businessResponsesApi.listRemovedBusinessResponses({
        page,
        size,
        removedBy: appliedFilters.removedBy || undefined,
        reviewId: appliedFilters.reviewId.trim() || undefined,
        discussionId: appliedFilters.discussionId.trim() || undefined,
        businessAccountId: appliedFilters.businessAccountId.trim() || undefined,
        placeId: appliedFilters.placeId.trim() || undefined,
      });

      // Se a consulta retornar a lista vazia e o número da página solicitada estiver fora do novo intervalo
      // (ex.: após uma operação reduzir o total de páginas), volta para a última página válida ou para zero.
      if (response.content.length === 0 && page > 0) {
        const isOutOfRange = response.totalPages === 0 || page >= response.totalPages;
        if (isOutOfRange) {
          const targetPage = response.totalPages > 0 ? response.totalPages - 1 : 0;
          if (targetPage !== page) {
            setPage(targetPage);
            return;
          }
        }
      }

      setResponses(response.content);
      setPage(response.pageNumber);
      setTotalPages(response.totalPages);
      setTotalElements(response.totalElements);
      setIsLast(response.isLast);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível carregar as respostas oficiais removidas.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [page, size, appliedFilters]);

  useEffect(() => {
    fetchResponses();
  }, [fetchResponses]);

  const handleApplyFilters = (e: React.FormEvent) => {
    e.preventDefault();
    setFeedbackSuccess(null);
    setPage(0);
    setAppliedFilters({
      removedBy: filterRemovedBy,
      reviewId: filterReviewId,
      discussionId: filterDiscussionId,
      businessAccountId: filterBusinessAccountId,
      placeId: filterPlaceId,
    });
  };

  const handleResetFilters = () => {
    setFeedbackSuccess(null);
    setFilterRemovedBy('');
    setFilterReviewId('');
    setFilterDiscussionId('');
    setFilterBusinessAccountId('');
    setFilterPlaceId('');
    setPage(0);
    setAppliedFilters({
      removedBy: '',
      reviewId: '',
      discussionId: '',
      businessAccountId: '',
      placeId: '',
    });
  };

  const handleRestoreSuccess = () => {
    setFeedbackSuccess('Resposta oficial restaurada com sucesso!');
    fetchResponses();
  };

  return (
    <div>
      {/* Cabeçalho */}
      <div
        className="header"
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          flexWrap: 'wrap',
          gap: '16px',
        }}
      >
        <div>
          <h1 className="title">Respostas Oficiais Removidas</h1>
          <p className="subtitle">
            Consulta administrativa e restauração de respostas oficiais de empresas à comunidade.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={fetchResponses}
          disabled={isLoading}
        >
          {isLoading ? 'Atualizando...' : '🔄 Atualizar'}
        </button>
      </div>

      {/* Banner de Feedback de Sucesso */}
      {feedbackSuccess && (
        <div
          role="status"
          className="card"
          style={{
            borderLeft: '4px solid var(--accent-success)',
            backgroundColor: 'rgba(16, 185, 129, 0.1)',
            padding: '14px 20px',
            marginBottom: '20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span style={{ fontSize: '1.2rem' }}>✓</span>
            <span style={{ color: 'var(--accent-success)', fontWeight: 600 }}>
              {feedbackSuccess}
            </span>
          </div>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => setFeedbackSuccess(null)}
            style={{ padding: '4px 8px', fontSize: '0.75rem' }}
          >
            Dispensar
          </button>
        </div>
      )}

      {/* Barra de Filtros */}
      <form onSubmit={handleApplyFilters} className="card" style={{ padding: '20px', marginBottom: '24px' }}>
        <h3 style={{ fontSize: '1rem', marginBottom: '14px', color: 'var(--text-secondary)' }}>
          🔍 Filtros de Consulta
        </h3>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
            gap: '16px',
            marginBottom: '16px',
          }}
        >
          <div>
            <label
              htmlFor="filter-removed-by"
              style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '4px' }}
            >
              Origem da Remoção:
            </label>
            <select
              id="filter-removed-by"
              className="input-field"
              value={filterRemovedBy}
              onChange={(e) => setFilterRemovedBy(e.target.value as BusinessResponseRemovalSource | '')}
              disabled={isLoading}
            >
              <option value="">Todas as origens</option>
              <option value="BUSINESS">Empresa (BUSINESS)</option>
              <option value="MODERATION">Moderação (MODERATION)</option>
            </select>
          </div>

          <div>
            <label
              htmlFor="filter-review-id"
              style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '4px' }}
            >
              ID da Avaliação:
            </label>
            <input
              id="filter-review-id"
              type="text"
              className="input-field"
              placeholder="UUID da avaliação"
              value={filterReviewId}
              onChange={(e) => setFilterReviewId(e.target.value)}
              disabled={isLoading}
            />
          </div>

          <div>
            <label
              htmlFor="filter-discussion-id"
              style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '4px' }}
            >
              ID da Discussão:
            </label>
            <input
              id="filter-discussion-id"
              type="text"
              className="input-field"
              placeholder="UUID da discussão"
              value={filterDiscussionId}
              onChange={(e) => setFilterDiscussionId(e.target.value)}
              disabled={isLoading}
            />
          </div>

          <div>
            <label
              htmlFor="filter-business-account-id"
              style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '4px' }}
            >
              ID da Conta da Empresa:
            </label>
            <input
              id="filter-business-account-id"
              type="text"
              className="input-field"
              placeholder="UUID da conta"
              value={filterBusinessAccountId}
              onChange={(e) => setFilterBusinessAccountId(e.target.value)}
              disabled={isLoading}
            />
          </div>

          <div>
            <label
              htmlFor="filter-place-id"
              style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '4px' }}
            >
              ID do Local:
            </label>
            <input
              id="filter-place-id"
              type="text"
              className="input-field"
              placeholder="UUID do local"
              value={filterPlaceId}
              onChange={(e) => setFilterPlaceId(e.target.value)}
              disabled={isLoading}
            />
          </div>
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={handleResetFilters}
            disabled={isLoading}
          >
            Limpar Filtros
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={isLoading}
          >
            Aplicar Filtros
          </button>
        </div>
      </form>

      {/* Estados Visuais de Carregamento e Erro */}
      {isLoading && <LoadingSpinner message="Carregando respostas oficiais removidas..." />}

      {errorMessage && (
        <ErrorMessage
          message={errorMessage}
          problem={problemDetail}
          onRetry={fetchResponses}
        />
      )}

      {/* Lista Vazia */}
      {!isLoading && !errorMessage && responses.length === 0 && (
        <div className="card" style={{ textAlign: 'center', padding: '48px 24px' }}>
          <span style={{ fontSize: '2.5rem', display: 'block', marginBottom: '12px' }}>✓</span>
          <h3 style={{ marginBottom: '6px' }}>Nenhuma resposta oficial removida encontrada</h3>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Não há registros correspondentes aos filtros aplicados na base de respostas oficiais removidas.
          </p>
        </div>
      )}

      {/* Lista de Registros */}
      {!isLoading && !errorMessage && responses.length > 0 && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '0.9rem' }}>
              <thead>
                <tr
                  style={{
                    borderBottom: '1px solid var(--border-color)',
                    backgroundColor: 'rgba(255, 255, 255, 0.02)',
                  }}
                >
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Empresa / Local</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Alvo Relacionado</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600, minWidth: '240px' }}>Conteúdo da Resposta</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600 }}>Origem & Datas</th>
                  <th style={{ padding: '14px 18px', color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'right', minWidth: '160px' }}>
                    Restauração
                  </th>
                </tr>
              </thead>
              <tbody>
                {responses.map((item) => {
                  const unrestorableReasons = !item.restorable ? getUnrestorableExplanations(item) : [];

                  return (
                    <tr
                      key={item.id}
                      style={{
                        borderBottom: '1px solid var(--border-color)',
                        transition: 'background-color 0.15s ease',
                      }}
                    >
                      {/* Empresa / Local */}
                      <td style={{ padding: '14px 18px', verticalAlign: 'top' }}>
                        <div style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: '2px' }}>
                          <span aria-hidden="true" style={{ marginRight: '6px' }}>🏢</span>
                          <span>{item.corporateName}</span>
                        </div>
                        <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginBottom: '6px' }}>
                          <span aria-hidden="true" style={{ marginRight: '4px' }}>📍</span>
                          <span>{item.placeName}</span>
                        </div>
                        <div style={{ fontFamily: 'monospace', fontSize: '0.72rem', color: 'var(--text-secondary)' }}>
                          Conta: {item.businessAccountId.slice(0, 8)}... | Local: {item.placeId.slice(0, 8)}...
                        </div>
                      </td>

                      {/* Alvo Relacionado */}
                      <td style={{ padding: '14px 18px', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                        {item.discussionId ? (
                          <>
                            <span className="badge badge-info" style={{ marginBottom: '6px', display: 'inline-block' }}>
                              Discussão
                            </span>
                            <div style={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                              Discussão: {item.discussionId.slice(0, 8)}...
                            </div>
                            <div style={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                              Avaliação: {item.reviewId ? `${item.reviewId.slice(0, 8)}...` : '-'}
                            </div>
                          </>
                        ) : (
                          <>
                            <span className="badge badge-info" style={{ marginBottom: '6px', display: 'inline-block' }}>
                              Avaliação
                            </span>
                            <div style={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                              Avaliação: {item.reviewId ? `${item.reviewId.slice(0, 8)}...` : '-'}
                            </div>
                          </>
                        )}
                      </td>

                      {/* Conteúdo da Resposta */}
                      <td style={{ padding: '14px 18px', verticalAlign: 'top' }}>
                        <div
                          style={{
                            backgroundColor: 'rgba(0, 0, 0, 0.2)',
                            borderLeft: '3px solid var(--accent-primary)',
                            padding: '8px 12px',
                            borderRadius: '4px',
                            fontSize: '0.85rem',
                            color: 'var(--text-primary)',
                            maxHeight: '120px',
                            overflowY: 'auto',
                            whiteSpace: 'pre-wrap',
                          }}
                        >
                          "{item.content}"
                        </div>
                      </td>

                      {/* Origem da Remoção & Datas */}
                      <td style={{ padding: '14px 18px', verticalAlign: 'top', fontSize: '0.85rem' }}>
                        <div style={{ marginBottom: '6px' }}>
                          {item.removedBy === 'MODERATION' ? (
                            <span className="badge badge-warning">Moderação</span>
                          ) : (
                            <span
                              className="badge"
                              style={{
                                backgroundColor: 'rgba(148, 163, 184, 0.15)',
                                color: 'var(--text-secondary)',
                              }}
                            >
                              Empresa
                            </span>
                          )}
                        </div>
                        <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                          Publicada: {formatDate(item.createdAt)}
                        </div>
                        <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                          Removida: {formatDate(item.removedAt)}
                        </div>
                        {item.moderatedAt && (
                          <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                            Moderada: {formatDate(item.moderatedAt)}
                          </div>
                        )}
                        {item.moderationJustification && (
                          <div
                            style={{
                              marginTop: '6px',
                              padding: '4px 8px',
                              backgroundColor: 'rgba(245, 158, 11, 0.08)',
                              borderRadius: '4px',
                              fontSize: '0.75rem',
                              color: 'var(--accent-warning)',
                            }}
                          >
                            <strong>Motivo:</strong> {item.moderationReasonCode || '-'}: {item.moderationJustification}
                          </div>
                        )}
                      </td>

                      {/* Restauração / Ação */}
                      <td style={{ padding: '14px 18px', verticalAlign: 'top', textAlign: 'right' }}>
                        <div style={{ marginBottom: '8px' }}>
                          {item.restorable ? (
                            <span className="badge badge-success">Restaurável</span>
                          ) : (
                            <span className="badge badge-danger">Não restaurável</span>
                          )}
                        </div>

                        {item.restorable ? (
                          <button
                            type="button"
                            className="btn btn-primary"
                            style={{ fontSize: '0.8rem', padding: '6px 12px' }}
                            onClick={() => setSelectedResponseForRestore(item)}
                          >
                            Restaurar Resposta
                          </button>
                        ) : (
                          <div>
                            <button
                              type="button"
                              className="btn btn-secondary"
                              style={{ fontSize: '0.8rem', padding: '6px 12px', opacity: 0.5, cursor: 'not-allowed' }}
                              disabled
                              title={unrestorableReasons.join(' ')}
                              aria-disabled="true"
                            >
                              Restaurar
                            </button>
                            {unrestorableReasons.map((reason) => (
                              <p
                                key={reason}
                                style={{
                                  color: 'var(--accent-danger)',
                                  fontSize: '0.75rem',
                                  marginTop: '6px',
                                  textAlign: 'right',
                                  maxWidth: '200px',
                                  marginLeft: 'auto',
                                  lineHeight: '1.2',
                                }}
                              >
                                {reason}
                              </p>
                            ))}
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* Paginação */}
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

      {/* Modal de Restauração */}
      {selectedResponseForRestore && (
        <RestoreBusinessResponseModal
          response={selectedResponseForRestore}
          isOpen={!!selectedResponseForRestore}
          onClose={() => setSelectedResponseForRestore(null)}
          onRestoreSuccess={handleRestoreSuccess}
          onRefreshRequired={fetchResponses}
        />
      )}
    </div>
  );
};
