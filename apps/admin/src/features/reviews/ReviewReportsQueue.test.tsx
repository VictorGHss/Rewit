import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReviewReportsQueue } from './ReviewReportsQueue';
import { moderationApi } from '../../core/api/moderation';
import type { AdminReportResponse, PagedResponse } from '../../core/types';

vi.mock('../../core/api/moderation', () => ({
  moderationApi: {
    listReviewReports: vi.fn(),
    getReviewContext: vi.fn(),
    moderateReview: vi.fn(),
  },
}));

describe('ReviewReportsQueue', () => {
  const sampleReports: PagedResponse<AdminReportResponse> = {
    content: [
      {
        id: 'report-uuid-1',
        reviewId: 'review-uuid-100',
        reviewAuthorUserId: 'sensitive-author-id-123',
        reporterUserId: 'sensitive-reporter-id-456',
        reason: 'SPAM',
        detail: 'Propaganda repetitiva sem relação com o estabelecimento.',
        status: 'PENDING',
        createdAt: '2026-10-09T12:00:00Z',
      },
      {
        id: 'report-uuid-2',
        reviewId: 'review-uuid-200',
        reviewAuthorUserId: 'sensitive-author-id-789',
        reporterUserId: 'sensitive-reporter-id-012',
        reason: 'HARASSMENT',
        detail: 'Ofensa direcionada aos atendentes.',
        status: 'ACCEPTED',
        createdAt: '2026-10-09T13:00:00Z',
      },
    ],
    pageNumber: 0,
    pageSize: 20,
    totalElements: 2,
    totalPages: 1,
    isLast: true,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lista denúncias de avaliações e nunca exibe IDs sensíveis de autor ou denunciante', async () => {
    vi.mocked(moderationApi.listReviewReports).mockResolvedValueOnce(sampleReports);

    render(<ReviewReportsQueue />);

    expect(screen.getByText(/carregando denúncias de avaliações/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Propaganda repetitiva sem relação com o estabelecimento.')).toBeInTheDocument();
      expect(screen.getByText('Ofensa direcionada aos atendentes.')).toBeInTheDocument();
      expect(screen.getAllByText('Spam').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Assédio').length).toBeGreaterThanOrEqual(1);
    });

    // PRIVACIDADE OBRIGATÓRIA: IDs de usuário não podem constar na interface
    expect(screen.queryByText('sensitive-author-id-123')).not.toBeInTheDocument();
    expect(screen.queryByText('sensitive-reporter-id-456')).not.toBeInTheDocument();
    expect(screen.queryByText('sensitive-author-id-789')).not.toBeInTheDocument();
    expect(screen.queryByText('sensitive-reporter-id-012')).not.toBeInTheDocument();
  });

  it('permite filtrar por status e motivo recarregando os dados a partir da página 0', async () => {
    const user = userEvent.setup();
    vi.mocked(moderationApi.listReviewReports).mockResolvedValue(sampleReports);

    render(<ReviewReportsQueue />);

    await waitFor(() => {
      expect(screen.getByText('Propaganda repetitiva sem relação com o estabelecimento.')).toBeInTheDocument();
    });

    const statusSelect = screen.getByLabelText(/status:/i);
    await user.selectOptions(statusSelect, 'ACCEPTED');

    await waitFor(() => {
      expect(moderationApi.listReviewReports).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          status: 'ACCEPTED',
        })
      );
    });

    const reasonSelect = screen.getByLabelText(/motivo:/i);
    await user.selectOptions(reasonSelect, 'SPAM');

    await waitFor(() => {
      expect(moderationApi.listReviewReports).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          reason: 'SPAM',
        })
      );
    });
  });

  it('exibe mensagem quando não há denúncias encontradas', async () => {
    vi.mocked(moderationApi.listReviewReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<ReviewReportsQueue />);

    await waitFor(() => {
      expect(screen.getByText(/nenhuma denúncia encontrada/i)).toBeInTheDocument();
    });
  });
});
