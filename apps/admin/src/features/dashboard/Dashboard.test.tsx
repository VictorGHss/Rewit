import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Dashboard } from './Dashboard';
import { moderationApi } from '../../core/api/moderation';
import { ApiError } from '../../core/api/client';

vi.mock('../../core/api/moderation', () => ({
  moderationApi: {
    listReviewReports: vi.fn(),
    listDiscussionReports: vi.fn(),
  },
}));

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('carrega e exibe as contagens reais de casos pendentes de avaliações e discussões', async () => {
    vi.mocked(moderationApi.listReviewReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 7,
      totalPages: 7,
      isLast: false,
    });

    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 4,
      totalPages: 4,
      isLast: false,
    });

    render(<Dashboard />);

    expect(screen.getByText(/consultando filas de moderação/i)).toBeInTheDocument();

    await waitFor(() => {
      // Total de pendências = 7 + 4 = 11
      expect(screen.getByText('11')).toBeInTheDocument();
      // Pendências de avaliações
      expect(screen.getByText('7')).toBeInTheDocument();
      // Pendências de discussões
      expect(screen.getByText('4')).toBeInTheDocument();
    });

    expect(moderationApi.listReviewReports).toHaveBeenCalledWith({
      page: 0,
      size: 1,
      status: 'PENDING',
    });
    expect(moderationApi.listDiscussionReports).toHaveBeenCalledWith({
      page: 0,
      size: 1,
      status: 'PENDING',
    });
  });

  it('permite navegar diretamente para as filas de moderação', async () => {
    const user = userEvent.setup();
    const onNavigateToReviews = vi.fn();
    const onNavigateToDiscussions = vi.fn();

    vi.mocked(moderationApi.listReviewReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 3,
      totalPages: 3,
      isLast: false,
    });
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 2,
      totalPages: 2,
      isLast: false,
    });

    render(
      <Dashboard
        onNavigateToReviews={onNavigateToReviews}
        onNavigateToDiscussions={onNavigateToDiscussions}
      />
    );

    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /acessar fila de avaliações/i }));
    expect(onNavigateToReviews).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: /acessar fila de discussões/i }));
    expect(onNavigateToDiscussions).toHaveBeenCalledTimes(1);
  });

  it('exibe mensagem de erro amigável e permite tentar novamente quando a busca falhar', async () => {
    const user = userEvent.setup();

    vi.mocked(moderationApi.listReviewReports).mockRejectedValueOnce(
      new ApiError(500, {
        status: 500,
        title: 'Internal Server Error',
        detail: 'Falha temporária ao consultar métricas.',
      })
    );
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<Dashboard />);

    await waitFor(() => {
      expect(screen.getByText('Falha temporária ao consultar métricas.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /tentar novamente/i })).toBeInTheDocument();
    });

    // Mock para a tentativa de retry
    vi.mocked(moderationApi.listReviewReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 1,
      totalPages: 1,
      isLast: true,
    });
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 1,
      totalElements: 1,
      totalPages: 1,
      isLast: true,
    });

    await user.click(screen.getByRole('button', { name: /tentar novamente/i }));

    await waitFor(() => {
      expect(screen.getByText('2')).toBeInTheDocument();
    });
  });
});
