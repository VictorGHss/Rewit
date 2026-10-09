import React, { useEffect, useState } from 'react';
import { claimsApi } from '../../core/api/claims';
import { ConfirmationModal } from '../common/ConfirmationModal';
import { ClaimStatusBadge, formatDate } from '../common/Badge';
import { ApiError } from '../../core/api/client';
import type {
  PlaceClaimResponse,
  PlaceClaimDecision,
  ProblemDetail,
} from '../../core/types';

interface PlaceClaimDecisionModalProps {
  claim: PlaceClaimResponse | null;
  isOpen: boolean;
  onClose: () => void;
  onDecisionComplete: () => void;
}

export const PlaceClaimDecisionModal: React.FC<PlaceClaimDecisionModalProps> = ({
  claim,
  isOpen,
  onClose,
  onDecisionComplete,
}) => {
  const [justification, setJustification] = useState('');
  const [pendingDecision, setPendingDecision] = useState<PlaceClaimDecision | null>(null);
  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);
  const [feedbackSuccess, setFeedbackSuccess] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setJustification('');
      setPendingDecision(null);
      setShowConfirmModal(false);
      setErrorMessage(null);
      setProblemDetail(undefined);
      setFeedbackSuccess(null);
    }
  }, [isOpen, claim]);

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

  if (!isOpen || !claim) return null;

  const isPending = claim.status === 'PENDING';
  const trimmedLength = justification.trim().length;
  const isJustificationValid = trimmedLength >= 15 && trimmedLength <= 1000;

  const handleOpenDecisionConfirmation = (decision: PlaceClaimDecision) => {
    if (!isJustificationValid) {
      setErrorMessage('A justificativa da decisão deve ter entre 15 e 1000 caracteres.');
      return;
    }
    setErrorMessage(null);
    setProblemDetail(undefined);
    setPendingDecision(decision);
    setShowConfirmModal(true);
  };

  const executeDecision = async () => {
    if (!pendingDecision || !isJustificationValid || isSubmitting) return;

    setIsSubmitting(true);
    setErrorMessage(null);
    setProblemDetail(undefined);
    setShowConfirmModal(false);

    try {
      const updatedClaim = await claimsApi.decidePlaceClaim(claim.id, {
        decision: pendingDecision,
        justification: justification.trim(),
      });

      const label = updatedClaim.status === 'APPROVED' ? 'aprovada' : 'rejeitada';
      setFeedbackSuccess(`Reivindicação ${label} com sucesso.`);
      setJustification('');
      onDecisionComplete();
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        setProblemDetail(err.problem);
      } else {
        setErrorMessage('Falha ao processar a decisão da reivindicação.');
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
        onClick={(e) => {
          if (e.target === e.currentTarget && !isSubmitting && !showConfirmModal) {
            onClose();
          }
        }}
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
          aria-labelledby="claim-modal-title"
          className="card"
          style={{
            width: '100%',
            maxWidth: '680px',
            maxHeight: '90vh',
            overflowY: 'auto',
            backgroundColor: 'var(--bg-secondary)',
            border: '1px solid var(--border-color)',
            boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.5)',
            margin: 0,
          }}
        >
          {/* Header do Modal */}
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'flex-start',
              borderBottom: '1px solid var(--border-color)',
              paddingBottom: '16px',
              marginBottom: '20px',
            }}
          >
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                <h2 id="claim-modal-title" style={{ fontSize: '1.25rem', fontWeight: 600 }}>
                  Reivindicação de Local
                </h2>
                <ClaimStatusBadge status={claim.status} />
              </div>
              <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                Solicitado em {formatDate(claim.createdAt)}
              </span>
            </div>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={isSubmitting}
              style={{ padding: '4px 10px', fontSize: '0.85rem' }}
              aria-label="Fechar modal"
            >
              ✕
            </button>
          </div>

          {/* Feedback de Sucesso */}
          {feedbackSuccess && (
            <div
              style={{
                backgroundColor: 'rgba(16, 185, 129, 0.15)',
                border: '1px solid var(--accent-success)',
                color: 'var(--accent-success)',
                padding: '12px 16px',
                borderRadius: '8px',
                marginBottom: '20px',
                fontSize: '0.9rem',
              }}
            >
              ✓ {feedbackSuccess}
            </div>
          )}

          {/* Feedback de Erro */}
          {errorMessage && (
            <div
              style={{
                backgroundColor: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid var(--accent-danger)',
                color: 'var(--accent-danger)',
                padding: '12px 16px',
                borderRadius: '8px',
                marginBottom: '20px',
                fontSize: '0.9rem',
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: problemDetail?.detail ? '4px' : '0' }}>
                ⚠️ {errorMessage}
              </div>
              {problemDetail?.detail && problemDetail.detail !== errorMessage && (
                <div style={{ fontSize: '0.85rem', opacity: 0.9 }}>{problemDetail.detail}</div>
              )}
            </div>
          )}

          {/* Informações da Empresa & Local */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
              gap: '16px',
              marginBottom: '20px',
            }}
          >
            <div
              style={{
                backgroundColor: 'rgba(255, 255, 255, 0.03)',
                padding: '14px',
                borderRadius: '8px',
                border: '1px solid var(--border-color)',
              }}
            >
              <span style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: 600 }}>
                Conta Comercial Solicitante
              </span>
              <div style={{ fontSize: '1.05rem', fontWeight: 600, marginTop: '4px' }}>
                {claim.corporateName}
              </div>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '2px' }}>
                Documento Fiscal: <strong style={{ color: 'var(--text-primary)' }}>{claim.taxId}</strong>
              </div>
            </div>

            <div
              style={{
                backgroundColor: 'rgba(255, 255, 255, 0.03)',
                padding: '14px',
                borderRadius: '8px',
                border: '1px solid var(--border-color)',
              }}
            >
              <span style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: 600 }}>
                Local Solicitado
              </span>
              <div style={{ fontSize: '1.05rem', fontWeight: 600, marginTop: '4px' }}>
                {claim.placeName}
              </div>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '2px' }}>
                {claim.city} / {claim.state}
              </div>
            </div>
          </div>

          {/* Justificativa de Evidências */}
          <div style={{ marginBottom: '24px' }}>
            <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-secondary)', display: 'block', marginBottom: '6px' }}>
              Descrição das Evidências de Representação:
            </span>
            <div
              style={{
                backgroundColor: 'rgba(255, 255, 255, 0.02)',
                padding: '14px 16px',
                borderRadius: '8px',
                border: '1px solid var(--border-color)',
                fontSize: '0.92rem',
                lineHeight: '1.6',
                color: 'var(--text-primary)',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              {claim.evidenceDescription}
            </div>
          </div>

          {/* Histórico se já decidida */}
          {!isPending && (
            <div
              style={{
                backgroundColor: 'rgba(255, 255, 255, 0.03)',
                padding: '16px',
                borderRadius: '8px',
                border: '1px solid var(--border-color)',
                marginBottom: '20px',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Decisão Registrada</span>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  Decidido em {formatDate(claim.decidedAt || undefined)}
                </span>
              </div>
              <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', margin: 0, whiteSpace: 'pre-wrap' }}>
                {claim.decisionReason || 'Sem justificativa informada.'}
              </p>
            </div>
          )}

          {/* Ações para Solicitação Pendente */}
          {isPending && (
            <div
              style={{
                borderTop: '1px solid var(--border-color)',
                paddingTop: '20px',
                marginTop: '10px',
              }}
            >
              <h3 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '8px' }}>
                Tomar Decisão Administrativa
              </h3>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '14px' }}>
                A aprovação transfere o controle do estabelecimento para a empresa solicitante. A rejeição arquiva a solicitação.
                A justificativa formal é obrigatória e deve ter entre 15 e 1.000 caracteres.
              </p>

              <div style={{ marginBottom: '16px' }}>
                <label
                  htmlFor="claim-justification-input"
                  style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '6px' }}
                >
                  Justificativa da Decisão:
                </label>
                <textarea
                  id="claim-justification-input"
                  className="input-field"
                  rows={4}
                  placeholder="Explique detalhadamente o fundamento da aprovação ou recusa desta reivindicação (mínimo 15 caracteres)..."
                  value={justification}
                  onChange={(e) => setJustification(e.target.value)}
                  disabled={isSubmitting}
                  style={{
                    width: '100%',
                    resize: 'vertical',
                    boxSizing: 'border-box',
                    borderColor: trimmedLength > 0 && !isJustificationValid ? 'var(--accent-danger)' : undefined,
                  }}
                />
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    fontSize: '0.75rem',
                    color: trimmedLength > 0 && !isJustificationValid ? 'var(--accent-danger)' : 'var(--text-secondary)',
                    marginTop: '4px',
                  }}
                >
                  <span>Mínimo 15 caracteres</span>
                  <span>{trimmedLength} / 1000</span>
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', flexWrap: 'wrap' }}>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={onClose}
                  disabled={isSubmitting}
                >
                  Cancelar
                </button>
                <button
                  type="button"
                  className="btn btn-danger"
                  onClick={() => handleOpenDecisionConfirmation('REJECT')}
                  disabled={isSubmitting || !isJustificationValid}
                >
                  ✕ Rejeitar Reivindicação
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => handleOpenDecisionConfirmation('APPROVE')}
                  disabled={isSubmitting || !isJustificationValid}
                >
                  ✓ Aprovar Reivindicação
                </button>
              </div>
            </div>
          )}

          {/* Botão de Fechar quando não for pendente */}
          {!isPending && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '10px' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={onClose}
              >
                Fechar
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Diálogo de Confirmação */}
      <ConfirmationModal
        isOpen={showConfirmModal}
        title={pendingDecision === 'APPROVE' ? 'Aprovar Reivindicação' : 'Rejeitar Reivindicação'}
        message={
          pendingDecision === 'APPROVE'
            ? `Deseja realmente aprovar a reivindicação do local "${claim.placeName}" pela empresa "${claim.corporateName}"? O estabelecimento passará a ser gerenciado por esta conta comercial.`
            : `Deseja realmente rejeitar a reivindicação do local "${claim.placeName}" pela empresa "${claim.corporateName}"? Esta decisão é definitiva e será arquivada.`
        }
        confirmLabel={pendingDecision === 'APPROVE' ? 'Confirmar Aprovação' : 'Confirmar Rejeição'}
        isDestructive={pendingDecision === 'REJECT'}
        isLoading={isSubmitting}
        onConfirm={executeDecision}
        onCancel={() => setShowConfirmModal(false)}
      />
    </>
  );
};
