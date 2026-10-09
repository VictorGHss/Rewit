import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DiscussionReportsQueue } from './DiscussionReportsQueue';
import { moderationApi } from '../../core/api/moderation';
import type { AdminDiscussionReportResponse, PagedResponse } from '../../core/types';

vi.mock('../../core/api/moderation', () => ({
  moderationApi: {
    listDiscussionReports: vi.fn(),
    getDiscussionContext: vi.fn(),
    moderateDiscussion: vi.fn(),
  },
}));

describe('DiscussionReportsQueue', () => {
  const sampleReports: PagedResponse<AdminDiscussionReportResponse> = {
    content: [
      {
        id: 'disc-rep-1',
        discussionId: 'disc-100',
        reviewId: 'review-1',
        parentId: null,
        discussionAuthorUserId: 'sensitive-author-disc-1',
        reporterUserId: 'sensitive-reporter-disc-1',
        discussionStatus: 'ACTIVE',
        reason: 'HATE_SPEECH',
        detail: 'Discurso ofensivo e discriminatório na discussão.',
        status: 'PENDING',
        createdAt: '2026-10-09T12:30:00Z',
      },
      {
        id: 'disc-rep-2',
        discussionId: 'disc-200',
        reviewId: 'review-1',
        parentId: 'disc-100',
        discussionAuthorUserId: 'sensitive-author-disc-2',
        reporterUserId: 'sensitive-reporter-disc-2',
        discussionStatus: 'ACTIVE',
        reason: 'SPAM',
        detail: 'Link malicioso enviado como resposta.',
        status: 'PENDING',
        createdAt: '2026-10-09T13:30:00Z',
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

  it('lista denúncias de discussões diferenciando raiz e resposta sem expor IDs sensíveis', async () => {
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValueOnce(sampleReports);

    render(<DiscussionReportsQueue />);

    expect(screen.getByText(/carregando denúncias de discussões/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Discurso ofensivo e discriminatório na discussão.')).toBeInTheDocument();
      expect(screen.getByText('Link malicioso enviado como resposta.')).toBeInTheDocument();
      expect(screen.getAllByText('Discurso de Ódio').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Spam').length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('Raiz')).toBeInTheDocument();
      expect(screen.getByText(/↳ Resposta/i)).toBeInTheDocument();
    });

    // PRIVACIDADE OBRIGATÓRIA: IDs de usuário não podem constar na interface
    expect(screen.queryByText('sensitive-author-disc-1')).not.toBeInTheDocument();
    expect(screen.queryByText('sensitive-reporter-disc-1')).not.toBeInTheDocument();
    expect(screen.queryByText('sensitive-author-disc-2')).not.toBeInTheDocument();
    expect(screen.queryByText('sensitive-reporter-disc-2')).not.toBeInTheDocument();
  });

  it('permite filtrar denúncias de discussões por status e motivo', async () => {
    const user = userEvent.setup();
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValue(sampleReports);

    render(<DiscussionReportsQueue />);

    await waitFor(() => {
      expect(screen.getByText('Discurso ofensivo e discriminatório na discussão.')).toBeInTheDocument();
    });

    const statusSelect = screen.getByLabelText(/status:/i);
    await user.selectOptions(statusSelect, 'ACCEPTED');

    await waitFor(() => {
      expect(moderationApi.listDiscussionReports).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          status: 'ACCEPTED',
        })
      );
    });

    const reasonSelect = screen.getByLabelText(/motivo:/i);
    await user.selectOptions(reasonSelect, 'HATE_SPEECH');

    await waitFor(() => {
      expect(moderationApi.listDiscussionReports).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          reason: 'HATE_SPEECH',
        })
      );
    });
  });

  it('exibe estado vazio quando nenhuma denúncia é retornada', async () => {
    vi.mocked(moderationApi.listDiscussionReports).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<DiscussionReportsQueue />);

    await waitFor(() => {
      expect(screen.getByText(/nenhuma denúncia encontrada/i)).toBeInTheDocument();
    });
  });
});
