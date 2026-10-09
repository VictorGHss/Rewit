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
      updatedAt: '2026-10-09T10:05:00Z',
      targets: [
        {
          targetId: 'target-uuid-place-1234',
          type: 'PLACE',
          displayName: 'Café das Flores',
          rating: 4.8,
          specificComment: 'Ambiente aconchegante e excelente música ambiente.',
        },
        {
          targetId: 'target-uuid-product-5678',
          type: 'PRODUCT',
          displayName: 'Cappuccino Italiano',
          rating: 4.5,
          specificComment: null,
        },
        {
          targetId: 'target-uuid-unspecialized-9999',
          type: null,
          displayName: null,
          rating: 3.5,
          specificComment: 'Atendimento geral sem especialização.',
        },
      ],
    },
    pendingReportCount: 1,
    reports: [
      {
        id: 'rep-uuid-1',
        reason: 'SPAM',
        detail: 'Suspeita de conta automatizada com comentários repetitivos.',
        status: 'PENDING',
        createdAt: '2026-10-09T11:00:00Z',
        updatedAt: '2026-10-09T11:00:00Z',
      },
    ],
    auditHistory: [
      {
        action: 'RESTORE_REVIEW',
        reasonCode: 'REPORT_DISMISSED',
        justification: 'Avaliação legítima verificada por evidências fotográficas.',
        previousStatus: 'UNDER_REVIEW',
        newStatus: 'ACTIVE',
        createdAt: '2026-10-08T09:00:00Z',
      },
      {
        action: 'REMOVE_REVIEW',
        reasonCode: 'TERMS_VIOLATION',
        justification: 'Suspeita de violação temporariamente moderada.',
        previousStatus: 'ACTIVE',
        newStatus: 'UNDER_REVIEW',
        createdAt: '2026-10-07T08:00:00Z',
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('exibe nome e tipo do alvo e trata alvos sem especialização sem utilizar UUID como rótulo principal', async () => {
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
      // Nome e tipo exibidos quando presentes
      expect(screen.getByText('Café das Flores')).toBeInTheDocument();
      expect(screen.getByText('PLACE')).toBeInTheDocument();
      expect(screen.getByText('Cappuccino Italiano')).toBeInTheDocument();
      expect(screen.getByText('PRODUCT')).toBeInTheDocument();

      // Alvo sem especialização (displayName null, type null) exibe fallback legível
      expect(screen.getByText('Alvo sem especialização')).toBeInTheDocument();
      expect(screen.getByText('Atendimento geral sem especialização.')).toBeInTheDocument();

      // Não exibe o UUID do alvo como rótulo principal quando há displayName
      expect(screen.queryByText('Alvo: target-uuid-place-1234')).not.toBeInTheDocument();
      expect(screen.queryByText('Alvo: target-uuid-product-5678')).not.toBeInTheDocument();
    });
  });

  it('renderiza o histórico de auditoria usando campos existentes sem dependência de auditHistory[].id', async () => {
    vi.mocked(moderationApi.getReviewContext).mockResolvedValueOnce(sampleContext);

    render(
      <ReviewContextModal
        reviewId="review-100"
        isOpen={true}
        onClose={vi.fn()}
        onModerationComplete={vi.fn()}
      />
    );

    await waitFor(() => {
      // Entradas do histórico
      expect(
        screen.getByText(/Ação: RESTORE_REVIEW \(REPORT_DISMISSED\)/i)
      ).toBeInTheDocument();
      expect(screen.getByText(/\[UNDER_REVIEW → ACTIVE\]/i)).toBeInTheDocument();
      expect(
        screen.getByText(/Justificativa: Avaliação legítima verificada por evidências fotográficas./i)
      ).toBeInTheDocument();

      expect(
        screen.getByText(/Ação: REMOVE_REVIEW \(TERMS_VIOLATION\)/i)
      ).toBeInTheDocument();
      expect(screen.getByText(/\[ACTIVE → UNDER_REVIEW\]/i)).toBeInTheDocument();
      expect(
        screen.getByText(/Justificativa: Suspeita de violação temporariamente moderada./i)
      ).toBeInTheDocument();
    });
  });

  it('nunca exibe IDs de autores, denunciantes ou moderadores na tela', async () => {
    vi.mocked(moderationApi.getReviewContext).mockResolvedValueOnce(sampleContext);

    render(
      <ReviewContextModal
        reviewId="review-100"
        isOpen={true}
        onClose={vi.fn()}
        onModerationComplete={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText('Excelente atendimento e ambiente super agradável.')).toBeInTheDocument();
    });

    // Garante que termos ou IDs sensíveis não vazam
    expect(screen.queryByText(/userId/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/moderatorUserId/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/reporterUserId/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/authorUserId/i)).not.toBeInTheDocument();
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
