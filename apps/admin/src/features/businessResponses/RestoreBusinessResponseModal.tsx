import React, { useState, useEffect } from 'react';
import { businessResponsesApi } from '../../core/api/businessResponses';
import { ConfirmationModal } from '../common/ConfirmationModal';
import { ErrorMessage } from '../common/ErrorMessage';
import { formatDate } from '../common/Badge';
import { ApiError } from '../../core/api/client';
import type {
  AdminRemovedBusinessResponse,
  ProblemDetail,
} from '../../core/types';

interface RestoreBusinessResponseModalProps {
  response: AdminRemovedBusinessResponse;
  isOpen: boolean;
  onClose: () => void;
  onRestoreSuccess: () => void;
}

export const RestoreBusinessResponseModal: React.FC<RestoreBusinessResponseModalProps> = ({
  response,
  isOpen,
  onClose,
  onRestoreSuccess,
}) => {
  const [reasonCode, setReasonCode] = useState('REPORT_DISMISSED');
  const [justification, setJustification] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [showConfirmation, setShowConfirmation] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  useEffect(() => {
    if (isOpen) {
      setReasonCode('REPORT_DISMISSED');
      setJustification('');
      setIsSubmitting(false);
      setShowConfirmation(false);
      setErrorMessage(null);
      setProblemDetail(undefined);
    }
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isSubmitting && !showConfirmation) {
        onClose();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isSubmitting, showConfirmation, onClose]);

  if (!isOpen) return null;

  const trimmedReason = reasonCode.trim();
  const trimmedJustification = justification.trim();

  const isReasonValid = trimmedReason.length > 0 && trimmedReason.length <= 64;
  const isJustificationValid =
    trimmedJustification.length >= 15 && trimmedJustification.length <= 1000;
  const isFormValid = isReasonValid && isJustificationValid;

  const handleOpenConfirmation = (e: React.FormEvent) => {
    e.preventDefault();
    if (!isFormValid || isSubmitting) return;
    setErrorMessage(null);
    setProblemDetail(undefined);
    setShowConfirmation(true);
  };

  const handleExecuteRestore = async () => {
    if (isSubmitting) return;

    setIsSubmitting(true);
    setErrorMessage(null);
    setProblemDetail(undefined);

    try {
      await businessResponsesApi.moderateBusinessResponse(response.id, {
        action: 'RESTORE_RESPONSE',
        reasonCode: trimmedReason,
        justification: trimmedJustification,
      });

      setShowConfirmation(false);
      onRestoreSuccess();
      onClose();
    } catch (err) {
      setShowConfirmation(false);
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Não foi possível restaurar a resposta oficial.');
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
          aria-labelledby="restore-modal-title"
          className="card"
          style={{
            width: '100%',
            maxWidth: '680px',
            maxHeight: '90vh',
            overflowY: 'auto',
            backgroundColor: 'var(--bg-secondary)',
            border: '1px solid var(--border-color)',
            boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.7)',
            margin: 0,
          }}
        >
          {/* Cabeçalho */}
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: '20px',
              borderBottom: '1px solid var(--border-color)',
              paddingBottom: '16px',
            }}
          >
            <div>
              <h2 id="restore-modal-title" style={{ fontSize: '1.35rem', fontWeight: 700 }}>
                Restaurar Resposta Oficial
              </h2>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginTop: '4px' }}>
                Revogação administrativa de remoção da resposta da empresa
              </p>
            </div>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={isSubmitting}
              style={{ padding: '6px 12px' }}
              aria-label="Fechar modal"
            >
              ✕
            </button>
          </div>

          {/* Mensagem de Erro (HTTP / Negócio) */}
          {errorMessage && (
            <ErrorMessage message={errorMessage} problem={problemDetail} />
          )}

          {/* Contexto da Resposta */}
          <div
            style={{
              backgroundColor: 'rgba(255, 255, 255, 0.02)',
              border: '1px solid var(--border-color)',
              borderRadius: '8px',
              padding: '16px',
              marginBottom: '20px',
              fontSize: '0.9rem',
            }}
          >
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '12px', marginBottom: '12px' }}>
              <div>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', display: 'block' }}>Empresa</span>
                <strong>{response.corporateName}</strong>
              </div>
              <div>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', display: 'block' }}>Local</span>
                <strong>{response.placeName}</strong>
              </div>
              <div>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', display: 'block' }}>Alvo Relacionado</span>
                <span>
                  {response.discussionId ? (
                    <>Discussão em avaliação ({response.discussionId.slice(0, 8)}...)</>
                  ) : (
                    <>Avaliação ({response.reviewId?.slice(0, 8) ?? '-'}...)</>
                  )}
                </span>
              </div>
              <div>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', display: 'block' }}>Removida em</span>
                <span>{formatDate(response.removedAt)}</span>
              </div>
            </div>

            <div style={{ marginTop: '8px' }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', display: 'block', marginBottom: '4px' }}>
                Conteúdo da resposta:
              </span>
              <div
                style={{
                  backgroundColor: 'rgba(0, 0, 0, 0.2)',
                  borderLeft: '3px solid var(--accent-primary)',
                  padding: '10px 14px',
                  borderRadius: '4px',
                  fontStyle: 'italic',
                  color: 'var(--text-primary)',
                  whiteSpace: 'pre-wrap',
                  maxHeight: '140px',
                  overflowY: 'auto',
                }}
              >
                "{response.content}"
              </div>
            </div>

            {response.moderationJustification && (
              <div style={{ marginTop: '12px', paddingTop: '10px', borderTop: '1px solid var(--border-color)' }}>
                <span style={{ color: 'var(--accent-warning)', fontSize: '0.8rem', fontWeight: 600, display: 'block' }}>
                  Motivo da remoção anterior ({response.moderationReasonCode || 'MODERATION'}):
                </span>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                  {response.moderationJustification}
                </span>
              </div>
            )}
          </div>

          {/* Formulário de Restauração */}
          <form onSubmit={handleOpenConfirmation}>
            <div style={{ marginBottom: '16px' }}>
              <label
                htmlFor="restore-reason-code"
                style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '6px' }}
              >
                Código do Motivo da Decisão (reasonCode) *
              </label>
              <input
                id="restore-reason-code"
                type="text"
                className="input-field"
                value={reasonCode}
                onChange={(e) => setReasonCode(e.target.value)}
                maxLength={64}
                disabled={isSubmitting}
                placeholder="Ex.: REPORT_DISMISSED"
                required
              />
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  fontSize: '0.75rem',
                  color: 'var(--text-secondary)',
                  marginTop: '4px',
                }}
              >
                <span>Padrão sugerido: REPORT_DISMISSED</span>
                <span>{trimmedReason.length}/64 caracteres</span>
              </div>
              {trimmedReason.length === 0 && (
                <span style={{ color: 'var(--accent-danger)', fontSize: '0.75rem' }}>
                  O código do motivo é obrigatório.
                </span>
              )}
            </div>

            <div style={{ marginBottom: '20px' }}>
              <label
                htmlFor="restore-justification"
                style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '6px' }}
              >
                Justificativa da Restauração *
              </label>
              <textarea
                id="restore-justification"
                className="input-field"
                rows={4}
                value={justification}
                onChange={(e) => setJustification(e.target.value)}
                maxLength={1000}
                disabled={isSubmitting}
                placeholder="Informe a justificativa formal para restaurar esta resposta (entre 15 e 1.000 caracteres)..."
                required
                style={{ resize: 'vertical' }}
              />
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  fontSize: '0.75rem',
                  color: 'var(--text-secondary)',
                  marginTop: '4px',
                }}
              >
                <span
                  style={{
                    color:
                      trimmedJustification.length > 0 && trimmedJustification.length < 15
                        ? 'var(--accent-danger)'
                        : 'var(--text-secondary)',
                  }}
                >
                  Mínimo de 15 caracteres (obrigatório)
                </span>
                <span>{trimmedJustification.length}/1000 caracteres</span>
              </div>
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
                className="btn btn-primary"
                disabled={!isFormValid || isSubmitting}
              >
                {isSubmitting ? 'Restaurando...' : 'Restaurar Resposta'}
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* Modal de Confirmação Prévia */}
      {showConfirmation && (
        <ConfirmationModal
          isOpen={showConfirmation}
          title="Confirmar Restauração"
          message={`Confirma a restauração da resposta oficial da empresa "${response.corporateName}"? Ela voltará a ser exibida publicamente.`}
          confirmLabel="Sim, Restaurar"
          cancelLabel="Voltar"
          isLoading={isSubmitting}
          onConfirm={handleExecuteRestore}
          onCancel={() => setShowConfirmation(false)}
        />
      )}
    </>
  );
};
