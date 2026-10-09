import React, { useEffect, useState, useCallback } from 'react';
import { moderationApi } from '../../core/api/moderation';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { ConfirmationModal } from '../common/ConfirmationModal';
import { StatusBadge, ReasonBadge, formatDate } from '../common/Badge';
import { ApiError } from '../../core/api/client';
import type {
  AdminReviewContextResponse,
  ModerationAction,
  ProblemDetail,
} from '../../core/types';

interface ReviewContextModalProps {
  reviewId: string;
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

export const ReviewContextModal: React.FC<ReviewContextModalProps> = ({
  reviewId,
  isOpen,
  onClose,
  onModerationComplete,
}) => {
  const [contextData, setContextData] = useState<AdminReviewContextResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  // Formulário de Moderação
  const [action, setAction] = useState<ModerationAction>('REMOVE_REVIEW');
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
      const data = await moderationApi.getReviewContext(reviewId);
      setContextData(data);
      // Se a avaliação já estiver removida, sugerir RESTORE_REVIEW como padrão
      if (data.review.status === 'REMOVED') {
        setAction('RESTORE_REVIEW');
        setReasonCode('REPORT_DISMISSED');
      } else {
        setAction('REMOVE_REVIEW');
        setReasonCode('TERMS_VIOLATION');
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível obter o contexto completo da avaliação.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [reviewId]);

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

    if (action === 'REMOVE_REVIEW') {
      setShowConfirmModal(true);
    } else {
      executeModeration();
    }
  };

  const executeModeration = async () => {
    setIsSubmitting(true);
    setErrorMessage(null);
    setProblemDetail(undefined);
    setFeedbackSuccess(null);
    setShowConfirmModal(false);

    try {
      const response = await moderationApi.moderateReview(reviewId, {
        action,
        reasonCode: reasonCode.trim(),
        justification: justification.trim(),
      });

      setFeedbackSuccess(
        `Ação "${response.action}" executada com sucesso. Novo status da avaliação: ${response.newStatus}.`
      );
      setJustification('');
      await loadContext();
      onModerationComplete();
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Falha ao processar a moderação da avaliação.');
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
          aria-labelledby="review-context-title"
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
            <h2 id="review-context-title" style={{ fontSize: '1.3rem', color: 'var(--text-primary)' }}>
              Contexto e Moderação de Avaliação
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

          {isLoading && <LoadingSpinner message="Carregando contexto e histórico da avaliação..." />}

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
              {/* Detalhes da Avaliação */}
              <div className="card" style={{ backgroundColor: 'var(--bg-card)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px', marginBottom: '12px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <StatusBadge status={contextData.review.status} />
                    <span className="badge" style={{ backgroundColor: 'rgba(255, 255, 255, 0.08)' }}>
                      {contextData.review.visibility}
                    </span>
                    {contextData.review.isVerifiedOnSite && (
                      <span className="badge badge-success">✓ Presença Confirmada</span>
                    )}
                    {contextData.review.isAnonymous && (
                      <span className="badge" style={{ backgroundColor: 'rgba(148, 163, 184, 0.2)', color: 'var(--text-secondary)' }}>
                        🔒 Autor Anônimo
                      </span>
                    )}
                  </div>
                  <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                    Publicada em: {formatDate(contextData.review.createdAt)}
                  </span>
                </div>

                <div style={{ marginBottom: '16px' }}>
                  <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', marginBottom: '4px' }}>
                    Relato de Experiência
                  </h4>
                  <p style={{ color: 'var(--text-primary)', fontSize: '0.95rem', lineHeight: '1.5', whiteSpace: 'pre-wrap' }}>
                    {contextData.review.experienceText || 'Sem texto de relato geral.'}
                  </p>
                </div>

                {contextData.review.targets && contextData.review.targets.length > 0 && (
                  <div>
                    <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', marginBottom: '8px' }}>
                      Alvos Avaliados ({contextData.review.targets.length})
                    </h4>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                      {contextData.review.targets.map((t, idx) => (
                        <div
                          key={idx}
                          style={{
                            padding: '8px 12px',
                            borderRadius: '8px',
                            backgroundColor: 'rgba(0, 0, 0, 0.2)',
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                          }}
                        >
                          <div>
                            <span style={{ fontWeight: 600, color: 'var(--text-primary)', fontSize: '0.9rem' }}>
                              Alvo: {t.targetId}
                            </span>
                            {t.comment || t.specificComment ? (
                              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '2px' }}>
                                {t.comment || t.specificComment}
                              </p>
                            ) : null}
                          </div>
                          <span style={{ color: 'var(--accent-warning)', fontWeight: 700, fontSize: '1rem' }}>
                            ★ {t.rating.toFixed(1)}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* Denúncias Relacionadas */}
              <div className="card" style={{ backgroundColor: 'var(--bg-card)' }}>
                <h3 style={{ fontSize: '1rem', marginBottom: '12px' }}>
                  Denúncias Vinculadas ({contextData.reports?.length ?? 0})
                </h3>
                {(!contextData.reports || contextData.reports.length === 0) ? (
                  <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                    Nenhuma denúncia associada a esta avaliação.
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
                          <span style={{ color: 'var(--text-secondary)' }}>{formatDate(audit.createdAt || audit.moderatedAt)}</span>
                        </div>
                        <p style={{ color: 'var(--text-secondary)' }}>Justificativa: {audit.justification}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Formulário de Ação de Moderação */}
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
                      <label htmlFor="review-action-select" style={{ display: 'block', fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                        Ação de Moderação *
                      </label>
                      <select
                        id="review-action-select"
                        className="input-field"
                        value={action}
                        onChange={(e) => setAction(e.target.value as ModerationAction)}
                        disabled={isSubmitting}
                      >
                        <option value="REMOVE_REVIEW">Remover Avaliação (REMOVE_REVIEW)</option>
                        <option value="RESTORE_REVIEW">Restaurar Avaliação (RESTORE_REVIEW)</option>
                      </select>
                    </div>

                    <div>
                      <label htmlFor="review-reason-select" style={{ display: 'block', fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                        Código do Motivo (reasonCode) *
                      </label>
                      <select
                        id="review-reason-select"
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
                      <label htmlFor="review-justification-input" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
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
                      id="review-justification-input"
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
                      className={`btn ${action === 'REMOVE_REVIEW' ? 'btn-danger' : 'btn-primary'}`}
                      disabled={!isFormValid || isSubmitting}
                    >
                      {isSubmitting
                        ? 'Enviando...'
                        : action === 'REMOVE_REVIEW'
                        ? 'Remover Avaliação'
                        : 'Restaurar Avaliação'}
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
        title="Confirmar Remoção da Avaliação"
        message="Esta ação marcará a avaliação como REMOVED, suspendendo sua exibição pública no feed e no local. Deseja prosseguir com a remoção?"
        confirmLabel="Sim, Remover Avaliação"
        isDestructive={true}
        isLoading={isSubmitting}
        onConfirm={executeModeration}
        onCancel={() => setShowConfirmModal(false)}
      />
    </>
  );
};
