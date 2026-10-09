import React from 'react';

interface PaginationProps {
  pageNumber: number; // 0-based
  totalPages: number;
  totalElements: number;
  isLast: boolean;
  onPageChange: (newPage: number) => void;
  isLoading?: boolean;
}

export const Pagination: React.FC<PaginationProps> = ({
  pageNumber,
  totalPages,
  totalElements,
  isLast,
  onPageChange,
  isLoading = false,
}) => {
  if (totalElements === 0) return null;

  const currentPageDisplay = pageNumber + 1;
  const displayTotalPages = Math.max(1, totalPages);

  return (
    <nav
      aria-label="Paginação da lista"
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        gap: '12px',
        padding: '16px 0',
        marginTop: '16px',
        borderTop: '1px solid var(--border-color)',
        color: 'var(--text-secondary)',
        fontSize: '0.9rem',
      }}
    >
      <div>
        <span>
          Mostrando página <strong>{currentPageDisplay}</strong> de{' '}
          <strong>{displayTotalPages}</strong> ({totalElements} {totalElements === 1 ? 'item' : 'itens'})
        </span>
      </div>

      <div style={{ display: 'flex', gap: '8px' }}>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => onPageChange(pageNumber - 1)}
          disabled={pageNumber === 0 || isLoading}
          aria-label="Página anterior"
          style={{ padding: '6px 14px', fontSize: '0.85rem' }}
        >
          ← Anterior
        </button>

        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => onPageChange(pageNumber + 1)}
          disabled={isLast || currentPageDisplay >= displayTotalPages || isLoading}
          aria-label="Próxima página"
          style={{ padding: '6px 14px', fontSize: '0.85rem' }}
        >
          Próxima →
        </button>
      </div>
    </nav>
  );
};
