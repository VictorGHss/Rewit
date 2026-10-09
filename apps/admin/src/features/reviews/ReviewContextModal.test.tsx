import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReviewContextModal } from './ReviewContextModal';
import { moderationApi } from '../../core/api/moderation';
import type { AdminReviewContextResponse } from '../../core/types';

vi.mock('../../core/api/moderation', () => ({
  moderationApi: {
    getReviewContext: vi.fn(),
    moderateReview: vi.fn(),
  },
}));

describe('ReviewContextModal', () => {
  const sampleContext: AdminReviewContextResponse = {
    review: {
      id: 'review-100',
      experienceText: 'Excelente atendimento e ambiente super agradável.',
      status: 'ACTIVE',
      visibility: 'PUBLIC',
      isAnonymous: true,
      isVerifiedOnSite: true,
      createdAt: '2026-10-09T10:00:00Z',
      targets: [
        {
          targetId: 'comida',
          rating: 4.5,
          comment: 'Sabor impecável',
        },
      ],
    },
    pendingReportCount: 1,
    reports: [
      {
        id: 'rep-1',
        reviewId: 'review-100',
        reason: 'SPAM',
        detail: 'Suspeita de conta automatizada.',
        status: 'PENDING',
        createdAt: '2026-10-09T11:00:00Z',
      },
    ],
    auditHistory: [
      {
        id: 'aud-1',
        action: 'RESTORE_REVIEW',
        reasonCode: 'REPORT_DISMISSED',
        justification: 'Avaliação legítima verificada por evidências.',
        createdAt: '2026-10-08T09:00:00Z',
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('carrega o contexto da avaliação com alvos, badges, denúncias e histórico', async () => {
    vi.mocked(moderationApi.getReviewContext).mockResolvedValueOnce(sampleContext);

    render(
      <ReviewContextModal
        reviewId="review-100"
        isOpen={true}
        onClose={vi.fn()}
        onModerationComplete={vi.fn()}
      />
    );

    expect(screen.getByText(/carregando contexto e histórico da avaliação/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Excelente atendimento e ambiente super agradável.')).toBeInTheDocument();
      expect(screen.getByText(/autor anônimo/i)).toBeInTheDocument();
      expect(screen.getByText(/presença confirmada/i)).toBeInTheDocument();
      expect(screen.getByText(/Alvo: comida/i)).toBeInTheDocument();
      expect(screen.getByText('Suspeita de conta automatizada.')).toBeInTheDocument();
      expect(screen.getByText(/Avaliação legítima verificada por evidências./i)).toBeInTheDocument();
    });
  });

  it('valida o tamanho mínimo da justificativa (15 caracteres) e executa remoção com modal de confirmação', async () => {
    const user = userEvent.setup();
    const onModerationComplete = vi.fn();

    vi.mocked(moderationApi.getReviewContext).mockResolvedValue(sampleContext);
    vi.mocked(moderationApi.moderateReview).mockResolvedValueOnce({
      auditLogId: 'audit-log-new',
      reviewId: 'review-100',
      action: 'REMOVE_REVIEW',
      reasonCode: 'TERMS_VIOLATION',
      justification: 'Violação explícita comprovada dos termos de serviço da plataforma.',
      previousStatus: 'ACTIVE',
      newStatus: 'REMOVED',
      resolvedReportsCount: 1,
      moderatedAt: '2026-10-09T14:00:00Z',
    });

    render(
      <ReviewContextModal
        reviewId="review-100"
        isOpen={true}
        onClose={vi.fn()}
        onModerationComplete={onModerationComplete}
      />
    );

    await waitFor(() => {
      expect(screen.getByText('Excelente atendimento e ambiente super agradável.')).toBeInTheDocument();
    });

    const submitBtn = screen.getByRole('button', { name: /remover avaliação/i });
    // Inicialmente a justificativa está vazia, o botão deve estar desabilitado
    expect(submitBtn).toBeDisabled();

    const justificationInput = screen.getByLabelText(/justificativa formal/i);

    // Texto com menos de 15 caracteres
    await user.type(justificationInput, 'Curto');
    expect(submitBtn).toBeDisabled();

    // Texto com mais de 15 caracteres
    await user.clear(justificationInput);
    await user.type(
      justificationInput,
      'Violação explícita comprovada dos termos de serviço da plataforma.'
    );
    expect(submitBtn).not.toBeDisabled();

    // Clica para remover
    await user.click(submitBtn);

    // Modal de confirmação deve aparecer
    expect(screen.getByText(/confirmar remoção da avaliação/i)).toBeInTheDocument();

    // Confirma no modal
    await user.click(screen.getByRole('button', { name: /sim, remover avaliação/i }));

    await waitFor(() => {
      expect(moderationApi.moderateReview).toHaveBeenCalledWith('review-100', {
        action: 'REMOVE_REVIEW',
        reasonCode: 'TERMS_VIOLATION',
        justification: 'Violação explícita comprovada dos termos de serviço da plataforma.',
      });
      expect(onModerationComplete).toHaveBeenCalledTimes(1);
    });
  });

  it('fecha ao pressionar a tecla Escape ou clicar no botão Fechar', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();

    vi.mocked(moderationApi.getReviewContext).mockResolvedValueOnce(sampleContext);

    render(
      <ReviewContextModal
        reviewId="review-100"
        isOpen={true}
        onClose={onClose}
        onModerationComplete={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText('Excelente atendimento e ambiente super agradável.')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /fechar janela/i }));
    expect(onClose).toHaveBeenCalledTimes(1);

    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
