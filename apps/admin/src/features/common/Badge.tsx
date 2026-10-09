import React from 'react';
import type { ReportReason, ReportStatus } from '../../core/types';

export function getReasonLabel(reason: ReportReason | string): string {
  switch (reason) {
    case 'SPAM':
      return 'Spam';
    case 'HARASSMENT':
      return 'Assédio';
    case 'HATE_SPEECH':
      return 'Discurso de Ódio';
    case 'MISINFORMATION':
      return 'Desinformação';
    case 'INAPPROPRIATE_CONTENT':
      return 'Conteúdo Inapropriado';
    case 'FRAUD':
      return 'Fraude';
    default:
      return reason;
  }
}

export function getStatusLabel(status: ReportStatus | string): string {
  switch (status) {
    case 'PENDING':
      return 'Pendente';
    case 'ACCEPTED':
      return 'Aceita (Procedente)';
    case 'REJECTED':
      return 'Rejeitada (Improcedente)';
    case 'ACTIVE':
      return 'Ativo';
    case 'UNDER_REVIEW':
      return 'Em Análise';
    case 'REMOVED':
      return 'Removido';
    default:
      return status;
  }
}

export function formatDate(dateString?: string): string {
  if (!dateString) return '-';
  try {
    const d = new Date(dateString);
    return d.toLocaleString('pt-BR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateString;
  }
}

export const StatusBadge: React.FC<{ status: ReportStatus | string }> = ({ status }) => {
  let badgeClass = 'badge';
  if (status === 'PENDING' || status === 'UNDER_REVIEW') {
    badgeClass += ' badge-warning';
  } else if (status === 'ACCEPTED' || status === 'ACTIVE') {
    badgeClass += ' badge-success';
  } else if (status === 'REJECTED' || status === 'REMOVED') {
    badgeClass += ' badge-danger';
  }

  return <span className={badgeClass}>{getStatusLabel(status)}</span>;
};

export const ReasonBadge: React.FC<{ reason: ReportReason | string }> = ({ reason }) => {
  return (
    <span
      className="badge"
      style={{
        backgroundColor: 'rgba(99, 102, 241, 0.15)',
        color: 'var(--accent-primary)',
      }}
    >
      {getReasonLabel(reason)}
    </span>
  );
};
