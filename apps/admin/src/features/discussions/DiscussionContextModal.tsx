import React, { useEffect, useState, useCallback } from 'react';
import { moderationApi } from '../../core/api/moderation';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { ConfirmationModal } from '../common/ConfirmationModal';
import { StatusBadge, ReasonBadge, formatDate } from '../common/Badge';
import { ApiError } from '../../core/api/client';
import type {
  AdminDiscussionContextResponse,
  DiscussionModerationAction,
  ProblemDetail,
} from '../../core/types';

interface DiscussionContextModalProps {
  discussionId: string;
  isOpen: boolean;
  onClose: () => void;
  onModerationComplete: () => void;
}

const COMMON_REASON_CODES = [
  { code: 'TERMS_VIOLATION', label: 'Violação dos Termos de Uso' },
  { code: 'INAPPROPRIATE_CONTENT', label: 'Conteúdo Impróprio / Ofensivo' },
  { code: 'HARASSMENT_OFFENSIVE', label: 'Assédio / Discurso de Ódio' },
  { code: 'SPAM_PROMOTION', label: 'Spam ou Propaganda Irregular' },
  { code: 'FALSE_INFORMATION', label: 'Desinformação / Difamação' },
  { code: 'REPORT_DISMISSED', label: 'Denúncia Improcedente / Regular' },
  { code: 'OTHER', label: 'Outro Motivo' },
];

export const DiscussionContextModal: React.FC<DiscussionContextModalProps> = ({
  discussionId,
  isOpen,
  onClose,
  onModerationComplete,
}) => {
  const [contextData, setContextData] = useState<AdminDiscussionContextResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  // Formulário de Moderação
  const [action, setAction] = useState<DiscussionModerationAction>('REMOVE_DISCUSSION');
  const [reasonCode, setReasonCode] = useState('TERMS_VIOLATION');
  const [justification, setJustification] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [feedbackSuccess, setFeedbackSuccess] = useState<string | null>(null);
  const [showConfirmModal, setShowConfirmModal] = useState(false);

  const loadContext = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);
    setFeedbackSuccess(null);

    try {
      const data = await moderationApi.getDiscussionContext(discussionId);
      setContextData(data);
      if (data.discussion.status === 'REMOVED') {
        setAction('RESTORE_DISCUSSION');
        setReasonCode('REPORT_DISMISSED');
      } else {
        setAction('REMOVE_DISCUSSION');
        setReasonCode('TERMS_VIOLATION');
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível obter o contexto da discussão.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [discussionId]);

  useEffect(() => {
    if (isOpen) {
      loadContext();
    }
  }, [isOpen, loadContext]);

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isSubmitting && !showConfirmModal) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isSubmitting, showConfirmModal, onClose]);

  if (!isOpen) return null;

  const isJustificationValid = justification.trim().length >= 15 && justification.trim().length <= 1000;
  const isFormValid = !!reasonCode.trim() && isJustificationValid;

  const handlePreSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!isFormValid || isSubmitting) return;
    setShowConfirmModal(true);
  };

  const executeModeration = async () => {
    setIsSubmitting(true);
    setErrorMessage(null);
    setProblemDetail(undefined);
    setFeedbackSuccess(null);
    setShowConfirmModal(false);

    try {
      const response = await moderationApi.moderateDiscussion(discussionId, {
        action,
        reasonCode: reasonCode.trim(),
        justification: justification.trim(),
      });

      setFeedbackSuccess(
        `Ação "${response.action}" executada com sucesso. Novo status da discussão: ${response.newStatus}.`
      );
      setJustification('');
      await loadContext();
      onModerationComplete();
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Falha ao processar a moderação da discussão.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <>
      <div
        className="modal-overlay"
        role="presentation"
        style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'rgba(0, 0, 0, 0.75)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 900,
          padding: '20px',
        }}
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="discussion-context-title"
          className="card"
          style={{
            width: '100%',
            maxWidth: '850px',
            maxHeight: '90vh',
            overflowY: 'auto',
            backgroundColor: 'var(--bg-secondary)',
            border: '1px solid var(--border-color)',
            boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.5)',
            margin: 0,
            padding: '28px',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
            <h2 id="discussion-context-title" style={{ fontSize: '1.3rem', color: 'var(--text-primary)' }}>
              Contexto e Moderação de Discussão
            </h2>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={isSubmitting}
              aria-label="Fechar janela"
              style={{ padding: '6px 12px' }}
            >
              ✕ Fechar
            </button>
          </div>

          {isLoading && <LoadingSpinner message="Carregando contexto e histórico da discussão..." />}

          {errorMessage && <ErrorMessage message={errorMessage} problem={problemDetail} onRetry={loadContext} />}

          {feedbackSuccess && (
            <div
              role="status"
              className="card"
              style={{
                backgroundColor: 'rgba(16, 185, 129, 0.12)',
                borderLeft: '4px solid var(--accent-success)',
                padding: '12px 16px',
                marginBottom: '16px',
                color: 'var(--accent-success)',
                fontSize: '0.9rem',
              }}
            >
              ✓ {feedbackSuccess}
            </div>
          )}

          {!isLoading && contextData && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
              {/* Discussão Pai (se houver) */}
              {contextData.parent && (
                <div
                  className="card"
                  style={{
                    backgroundColor: 'rgba(0, 0, 0, 0.25)',
                    borderLeft: '4px solid var(--accent-primary)',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--accent-primary)' }}>
                        Mensagem Original / Contexto Pai
                      </span>
                      <StatusBadge status={contextData.parent.status} />
                      {contextData.parent.isFromOwner && (
                        <span className="badge badge-warning">🏢 Proprietário</span>
                      )}
                    </div>
                    <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      {formatDate(contextData.parent.createdAt)}
                    </span>
                  </div>
                  <p style={{ color: 'var(--text-primary)', fontSize: '0.9rem', whiteSpace: 'pre-wrap' }}>
                    {contextData.parent.content}
                  </p>
                </div>
              )}

              {/* Detalhes da Discussão Alvo */}
              <div className="card" style={{ backgroundColor: 'var(--bg-card)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px', marginBottom: '12px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <StatusBadge status={contextData.discussion.status} />
                    {contextData.discussion.isFromOwner ? (
                      <span className="badge badge-warning">🏢 Proprietário do Local</span>
                    ) : (
                      <span className="badge" style={{ backgroundColor: 'rgba(255, 255, 255, 0.08)' }}>
                        Membro da Comunidade
                      </span>
                    )}
                    {contextData.discussion.parentId ? (
                      <span className="badge" style={{ backgroundColor: 'rgba(99, 102, 241, 0.15)', color: 'var(--accent-primary)' }}>
                        ↳ Resposta
                      </span>
                    ) : (
                      <span className="badge" style={{ backgroundColor: 'rgba(16, 185, 129, 0.15)', color: 'var(--accent-success)' }}>
                        Discussão Raiz
                      </span>
                    )}
                  </div>
                  <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                    Publicada em: {formatDate(contextData.discussion.createdAt)}
                  </span>
                </div>

                <div style={{ marginBottom: '8px' }}>
                  <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', marginBottom: '6px' }}>
                    Conteúdo da Discussão
                  </h4>
                  <p
                    style={{
                      color: 'var(--text-primary)',
                      fontSize: '0.95rem',
                      lineHeight: '1.5',
                      whiteSpace: 'pre-wrap',
                      backgroundColor: 'rgba(0, 0, 0, 0.15)',
                      padding: '12px',
                      borderRadius: '8px',
                    }}
                  >
                    {contextData.discussion.content || 'Sem conteúdo textual.'}
                  </p>
                </div>
              </div>

              {/* Denúncias Recebidas */}
              <div className="card" style={{ backgroundColor: 'var(--bg-card)' }}>
                <h3 style={{ fontSize: '1rem', marginBottom: '12px' }}>
                  Denúncias Recebidas ({contextData.reports?.length ?? 0})
                </h3>
                {(!contextData.reports || contextData.reports.length === 0) ? (
                  <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                    Nenhuma denúncia registrada para esta discussão.
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {contextData.reports.map((report) => (
                      <div
                        key={report.id}
                        style={{
                          padding: '10px 14px',
                          borderRadius: '8px',
                          border: '1px solid var(--border-color)',
                          backgroundColor: 'rgba(0, 0, 0, 0.15)',
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                            <ReasonBadge reason={report.reason} />
                            <StatusBadge status={report.status} />
                          </div>
                          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                            {formatDate(report.createdAt)}
                          </span>
                        </div>
                        <p style={{ color: 'var(--text-primary)', fontSize: '0.9rem' }}>
                          {report.detail || 'Sem detalhamento adicional.'}
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Histórico de Auditoria */}
              {contextData.auditHistory && contextData.auditHistory.length > 0 && (
                <div className="card" style={{ backgroundColor: 'var(--bg-card)' }}>
                  <h3 style={{ fontSize: '1rem', marginBottom: '12px' }}>Histórico de Decisões de Moderação</h3>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {contextData.auditHistory.map((audit) => (
                      <div
                        key={audit.id}
                        style={{
                          padding: '8px 12px',
                          borderRadius: '8px',
                          backgroundColor: 'rgba(0, 0, 0, 0.2)',
                          fontSize: '0.85rem',
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                          <strong>Ação: {audit.action} ({audit.reasonCode})</strong>
                          <span style={{ color: 'var(--text-secondary)' }}>{formatDate(audit.createdAt)}</span>
                        </div>
                        <p style={{ color: 'var(--text-secondary)' }}>Justificativa: {audit.justification}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Formulário de Moderação */}
              <div
                className="card"
                style={{
                  border: '1px solid var(--accent-primary)',
                  backgroundColor: 'rgba(99, 102, 241, 0.04)',
                }}
              >
                <h3 style={{ fontSize: '1.1rem', marginBottom: '16px', color: 'var(--text-primary)' }}>
                  Executar Decisão de Moderação
                </h3>

                <form onSubmit={handlePreSubmit}>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '16px', marginBottom: '16px' }}>
                    <div>
                      <label htmlFor="discussion-action-select" style={{ display: 'block', fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                        Ação de Moderação *
                      </label>
                      <select
                        id="discussion-action-select"
                        className="input-field"
                        value={action}
                        onChange={(e) => setAction(e.target.value as DiscussionModerationAction)}
                        disabled={isSubmitting}
                      >
                        <option value="REMOVE_DISCUSSION">Remover Discussão (REMOVE_DISCUSSION)</option>
                        <option value="RESTORE_DISCUSSION">Restaurar Discussão (RESTORE_DISCUSSION)</option>
                      </select>
                    </div>

                    <div>
                      <label htmlFor="discussion-reason-select" style={{ display: 'block', fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                        Código do Motivo (reasonCode) *
                      </label>
                      <select
                        id="discussion-reason-select"
                        className="input-field"
                        value={reasonCode}
                        onChange={(e) => setReasonCode(e.target.value)}
                        disabled={isSubmitting}
                      >
                        {COMMON_REASON_CODES.map((rc) => (
                          <option key={rc.code} value={rc.code}>
                            {rc.label} ({rc.code})
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>

                  <div style={{ marginBottom: '16px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
                      <label htmlFor="discussion-justification-input" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                        Justificativa Formal da Decisão (mínimo 15 caracteres) *
                      </label>
                      <span
                        style={{
                          fontSize: '0.8rem',
                          color: isJustificationValid ? 'var(--text-secondary)' : 'var(--accent-danger)',
                        }}
                      >
                        {justification.length} / 1000 caracteres
                      </span>
                    </div>
                    <textarea
                      id="discussion-justification-input"
                      className="input-field"
                      rows={3}
                      value={justification}
                      onChange={(e) => setJustification(e.target.value)}
                      placeholder="Descreva a motivação legal, conformidade com os termos e fundamento da decisão de moderação..."
                      disabled={isSubmitting}
                    />
                  </div>

                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
                    <button
                      type="button"
                      className="btn btn-secondary"
                      onClick={onClose}
                      disabled={isSubmitting}
                    >
                      Cancelar
                    </button>
                    <button
                      type="submit"
                      className={`btn ${action === 'REMOVE_DISCUSSION' ? 'btn-danger' : 'btn-primary'}`}
                      disabled={!isFormValid || isSubmitting}
                    >
                      {isSubmitting
                        ? 'Enviando...'
                        : action === 'REMOVE_DISCUSSION'
                        ? 'Remover Discussão'
                        : 'Restaurar Discussão'}
                    </button>
                  </div>
                </form>
              </div>
            </div>
          )}
        </div>
      </div>

      <ConfirmationModal
        isOpen={showConfirmModal}
        title={action === 'REMOVE_DISCUSSION' ? 'Confirmar Remoção da Discussão' : 'Confirmar Restauração da Discussão'}
        message={
          action === 'REMOVE_DISCUSSION'
            ? 'Esta ação marcará a discussão como REMOVED, suspendendo sua exibição pública. Deseja prosseguir com a remoção?'
            : 'Esta ação restaurará a discussão para ACTIVE, tornando-a visível novamente. Deseja prosseguir com a restauração?'
        }
        confirmLabel={action === 'REMOVE_DISCUSSION' ? 'Sim, Remover Discussão' : 'Sim, Restaurar Discussão'}
        isDestructive={action === 'REMOVE_DISCUSSION'}
        isLoading={isSubmitting}
        onConfirm={executeModeration}
        onCancel={() => setShowConfirmModal(false)}
      />
    </>
  );
};
