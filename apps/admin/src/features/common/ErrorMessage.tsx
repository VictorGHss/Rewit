import React from 'react';
import type { ProblemDetail } from '../../core/types';

interface ErrorMessageProps {
  message?: string;
  problem?: ProblemDetail;
  onRetry?: () => void;
}

export const ErrorMessage: React.FC<ErrorMessageProps> = ({
  message,
  problem,
  onRetry,
}) => {
  const displayTitle = problem?.title || 'Erro na operação';
  const displayDetail =
    message || problem?.detail || 'Ocorreu um erro ao processar a requisição.';

  return (
    <div
      role="alert"
      className="card"
      style={{
        borderLeft: '4px solid var(--accent-danger)',
        backgroundColor: 'rgba(239, 68, 68, 0.08)',
        margin: '16px 0',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
        <span style={{ fontSize: '1.4rem' }}>⚠️</span>
        <div style={{ flex: 1 }}>
          <h4 style={{ color: 'var(--accent-danger)', marginBottom: '4px', fontSize: '1rem' }}>
            {displayTitle}
          </h4>
          <p style={{ color: 'var(--text-primary)', fontSize: '0.9rem', lineHeight: '1.4' }}>
            {displayDetail}
          </p>

          {problem?.fieldErrors && problem.fieldErrors.length > 0 && (
            <ul style={{ marginTop: '8px', paddingLeft: '20px', color: 'var(--accent-danger)', fontSize: '0.85rem' }}>
              {problem.fieldErrors.map((fe, idx) => (
                <li key={idx}>
                  <strong>{fe.field}:</strong> {fe.message}
                </li>
              ))}
            </ul>
          )}

          {onRetry && (
            <button
              onClick={onRetry}
              className="btn btn-secondary"
              style={{ marginTop: '12px', padding: '6px 14px', fontSize: '0.85rem' }}
            >
              Tentar novamente
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
