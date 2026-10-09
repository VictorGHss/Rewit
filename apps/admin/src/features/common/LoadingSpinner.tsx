import React from 'react';

export const LoadingSpinner: React.FC<{ message?: string }> = ({
  message = 'Carregando dados...',
}) => {
  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '40px 20px',
        gap: '12px',
        color: 'var(--text-secondary)',
      }}
    >
      <div
        className="spinner"
        style={{
          width: '32px',
          height: '32px',
          border: '3px solid var(--border-color)',
          borderTopColor: 'var(--accent-primary)',
          borderRadius: '50%',
          animation: 'spin 0.8s linear infinite',
        }}
      />
      <span style={{ fontSize: '0.9rem' }}>{message}</span>
    </div>
  );
};
