import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DiscussionContextModal } from './DiscussionContextModal';
import { moderationApi } from '../../core/api/moderation';
import type { AdminDiscussionContextResponse } from '../../core/types';

vi.mock('../../core/api/moderation', () => ({
  moderationApi: {
    getDiscussionContext: vi.fn(),
    moderateDiscussion: vi.fn(),
  },
}));

describe('DiscussionContextModal', () => {
  const sampleContextWithParent: AdminDiscussionContextResponse = {
    discussion: {
      id: 'disc-200',
      reviewId: 'review-1',
      parentId: 'disc-100',
      content: 'Resposta com conteúdo inadequado e ofensivo.',
      status: 'ACTIVE',
      isFromOwner: false,
      createdAt: '2026-10-09T14:00:00Z',
    },
    parent: {
      id: 'disc-100',
      reviewId: 'review-1',
      parentId: null,
      content: 'Mensagem original postada pelo proprietário.',
      status: 'ACTIVE',
      isFromOwner: true,
      createdAt: '2026-10-09T13:00:00Z',
    },
    pendingReportCount: 1,
    reports: [
      {
        id: 'rep-disc-1',
        reason: 'INAPPROPRIATE_CONTENT',
        detail: 'Linguagem vulgar e ameaça velada.',
        status: 'PENDING',
        createdAt: '2026-10-09T14:30:00Z',
      },
    ],
    auditHistory: [
      {
        id: 'aud-disc-1',
        action: 'RESTORE_DISCUSSION',
        reasonCode: 'REPORT_DISMISSED',
        justification: 'Mensagem avaliada previamente como regular.',
        previousStatus: 'UNDER_REVIEW',
        newStatus: 'ACTIVE',
        reportsAffectedCount: 1,
        createdAt: '2026-10-09T12:00:00Z',
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('exibe o conteúdo da discussão e o contexto da discussão-pai quando houver', async () => {
    vi.mocked(moderationApi.getDiscussionContext).mockResolvedValueOnce(sampleContextWithParent);

    render(
      <DiscussionContextModal
        discussionId="disc-200"
        isOpen={true}
        onClose={vi.fn()}
        onModerationComplete={vi.fn()}
      />
    );

    expect(screen.getByText(/carregando contexto e histórico da discussão/i)).toBeInTheDocument();

    await waitFor(() => {
      // Conteúdo da discussão alvo
      expect(screen.getByText('Resposta com conteúdo inadequado e ofensivo.')).toBeInTheDocument();
      // Contexto da discussão pai
      expect(screen.getByText(/mensagem original \/ contexto pai/i)).toBeInTheDocument();
      expect(screen.getByText('Mensagem original postada pelo proprietário.')).toBeInTheDocument();
      // Denúncia
      expect(screen.getByText('Linguagem vulgar e ameaça velada.')).toBeInTheDocument();
      // Histórico
      expect(screen.getByText(/Mensagem avaliada previamente como regular./i)).toBeInTheDocument();
    });
  });

  it('valida justificativa e executa remoção de discussão com diálogo de confirmação', async () => {
    const user = userEvent.setup();
    const onModerationComplete = vi.fn();

    vi.mocked(moderationApi.getDiscussionContext).mockResolvedValue(sampleContextWithParent);
    vi.mocked(moderationApi.moderateDiscussion).mockResolvedValueOnce({
      auditLogId: 'audit-disc-new',
      discussionId: 'disc-200',
      action: 'REMOVE_DISCUSSION',
      reasonCode: 'INAPPROPRIATE_CONTENT',
      justification: 'Conteúdo comprovadamente em desconformidade com as diretrizes.',
      previousStatus: 'ACTIVE',
      newStatus: 'REMOVED',
      resolvedReportsCount: 1,
      moderatedAt: '2026-10-09T15:00:00Z',
    });

    render(
      <DiscussionContextModal
        discussionId="disc-200"
        isOpen={true}
        onClose={vi.fn()}
        onModerationComplete={onModerationComplete}
      />
    );

    await waitFor(() => {
      expect(screen.getByText('Resposta com conteúdo inadequado e ofensivo.')).toBeInTheDocument();
    });

    const submitBtn = screen.getByRole('button', { name: /remover discussão/i });
    expect(submitBtn).toBeDisabled();

    const justificationInput = screen.getByLabelText(/justificativa formal/i);
    await user.type(
      justificationInput,
      'Conteúdo comprovadamente em desconformidade com as diretrizes.'
    );
    expect(submitBtn).not.toBeDisabled();

    // Seleciona motivo adequado
    const reasonSelect = screen.getByLabelText(/código do motivo/i);
    await user.selectOptions(reasonSelect, 'INAPPROPRIATE_CONTENT');

    await user.click(submitBtn);

    // Diálogo de confirmação
    expect(screen.getByText(/confirmar remoção da discussão/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /sim, remover discussão/i }));

    await waitFor(() => {
      expect(moderationApi.moderateDiscussion).toHaveBeenCalledWith('disc-200', {
        action: 'REMOVE_DISCUSSION',
        reasonCode: 'INAPPROPRIATE_CONTENT',
        justification: 'Conteúdo comprovadamente em desconformidade com as diretrizes.',
      });
      expect(onModerationComplete).toHaveBeenCalledTimes(1);
    });
  });
});
