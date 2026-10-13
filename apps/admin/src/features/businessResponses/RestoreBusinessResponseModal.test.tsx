import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RestoreBusinessResponseModal } from './RestoreBusinessResponseModal';
import { businessResponsesApi } from '../../core/api/businessResponses';
import { ApiError } from '../../core/api/client';
import type {
  AdminRemovedBusinessResponse,
  ModerateBusinessResponseResponse,
} from '../../core/types';

vi.mock('../../core/api/businessResponses', () => ({
  businessResponsesApi: {
    moderateBusinessResponse: vi.fn(),
  },
}));

describe('RestoreBusinessResponseModal', () => {
  const sampleResponse: AdminRemovedBusinessResponse = {
    id: 'resp-uuid-1',
    reviewId: 'review-uuid-10',
    discussionId: null,
    businessAccountId: 'biz-acc-uuid-20',
    corporateName: 'Pizzaria Bella Italia Ltda',
    placeId: 'place-uuid-30',
    placeName: 'Bella Italia Jardins',
    content: 'Pedimos sinceras desculpas pelo ocorrido em seu pedido.',
    createdAt: '2026-10-10T14:00:00Z',
    removedBy: 'MODERATION',
    removedAt: '2026-10-11T16:00:00Z',
    restorable: true,
    otherActiveResponse: false,
    moderationReasonCode: 'INAPPROPRIATE_CONTENT',
    moderationJustification: 'Remoção preventiva por denúncia de violação de termos.',
    moderatedAt: '2026-10-11T16:00:00Z',
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renderiza os dados da resposta com razão padrão REPORT_DISMISSED', () => {
    render(
      <RestoreBusinessResponseModal
        response={sampleResponse}
        isOpen={true}
        onClose={vi.fn()}
        onRestoreSuccess={vi.fn()}
      />
    );

    expect(screen.getByText('Pizzaria Bella Italia Ltda')).toBeInTheDocument();
    expect(screen.getByText('Bella Italia Jardins')).toBeInTheDocument();
    expect(screen.getByText(/"Pedimos sinceras desculpas pelo ocorrido em seu pedido\."/)).toBeInTheDocument();
    expect(screen.getByText(/Remoção preventiva por denúncia de violação de termos\./)).toBeInTheDocument();

    const reasonInput = screen.getByLabelText(/código do motivo da decisão/i) as HTMLInputElement;
    expect(reasonInput.value).toBe('REPORT_DISMISSED');
  });

  it('valida reasonCode e comprimento da justificativa (mínimo 15 caracteres)', async () => {
    const user = userEvent.setup();

    render(
      <RestoreBusinessResponseModal
        response={sampleResponse}
        isOpen={true}
        onClose={vi.fn()}
        onRestoreSuccess={vi.fn()}
      />
    );

    const submitBtn = screen.getByRole('button', { name: /restaurar resposta/i });
    expect(submitBtn).toBeDisabled();

    const justificationInput = screen.getByLabelText(/justificativa da restauração/i);

    // Menos de 15 caracteres: permanece desabilitado
    await user.type(justificationInput, 'Curto demais');
    expect(submitBtn).toBeDisabled();

    // Atinge 15 caracteres válidos: deve habilitar
    await user.type(justificationInput, ' e agora possui mais de quinze caracteres');
    expect(submitBtn).not.toBeDisabled();

    // Se reasonCode for limpo: deve desabilitar novamente
    const reasonInput = screen.getByLabelText(/código do motivo da decisão/i);
    await user.clear(reasonInput);
    expect(submitBtn).toBeDisabled();
    expect(screen.getByText(/o código do motivo é obrigatório/i)).toBeInTheDocument();

    // Restaurando reasonCode válido
    await user.type(reasonInput, 'APPEAL_ACCEPTED');
    expect(submitBtn).not.toBeDisabled();
  });

  it('solicita confirmação prévia e envia payload RESTORE_RESPONSE correto em caso de sucesso', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onRestoreSuccess = vi.fn();

    vi.mocked(businessResponsesApi.moderateBusinessResponse).mockResolvedValueOnce({
      auditLogId: 'audit-log-1',
      responseId: sampleResponse.id,
      reviewId: sampleResponse.reviewId!,
      businessAccountId: sampleResponse.businessAccountId,
      placeId: sampleResponse.placeId,
      action: 'RESTORE_RESPONSE',
      reasonCode: 'REPORT_DISMISSED',
      justification: 'Justificativa formal com mais de 15 caracteres preenchida.',
      previousStatus: 'REMOVED',
      newStatus: 'ACTIVE',
      moderatedAt: '2026-10-13T12:00:00Z',
    });

    render(
      <RestoreBusinessResponseModal
        response={sampleResponse}
        isOpen={true}
        onClose={onClose}
        onRestoreSuccess={onRestoreSuccess}
      />
    );

    const justificationInput = screen.getByLabelText(/justificativa da restauração/i);
    await user.type(justificationInput, 'Justificativa formal com mais de 15 caracteres preenchida.');

    await user.click(screen.getByRole('button', { name: /restaurar resposta/i }));

    // Diálogo de confirmação aberto
    expect(screen.getByRole('dialog', { name: /confirmar restauração/i })).toBeInTheDocument();
    expect(screen.getByText(/confirma a restauração da resposta oficial da empresa "pizzaria bella italia ltda"\?/i)).toBeInTheDocument();

    // Confirmar ação
    await user.click(screen.getByRole('button', { name: /sim, restaurar/i }));

    await waitFor(() => {
      expect(businessResponsesApi.moderateBusinessResponse).toHaveBeenCalledWith(
        sampleResponse.id,
        {
          action: 'RESTORE_RESPONSE',
          reasonCode: 'REPORT_DISMISSED',
          justification: 'Justificativa formal com mais de 15 caracteres preenchida.',
        }
      );
      expect(onRestoreSuccess).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('exibe erro de negócio (ex.: 409 conflito) sem fechar o modal e sem perder os dados', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onRestoreSuccess = vi.fn();

    const conflictError = new ApiError(409, {
      title: 'Conflito de Regra de Negócio',
      detail: 'A empresa já tem outra resposta ativa neste alvo; a resposta removida não pode ser restaurada',
      status: 409,
    });

    vi.mocked(businessResponsesApi.moderateBusinessResponse).mockRejectedValueOnce(conflictError);

    render(
      <RestoreBusinessResponseModal
        response={sampleResponse}
        isOpen={true}
        onClose={onClose}
        onRestoreSuccess={onRestoreSuccess}
      />
    );

    const justificationInput = screen.getByLabelText(/justificativa da restauração/i);
    await user.type(justificationInput, 'Justificativa de teste válida com mais de 15 caracteres.');

    await user.click(screen.getByRole('button', { name: /restaurar resposta/i }));
    await user.click(screen.getByRole('button', { name: /sim, restaurar/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(screen.getByText(/a empresa já tem outra resposta ativa neste alvo/i)).toBeInTheDocument();
    });

    // O modal continua aberto e os callbacks de sucesso não foram chamados
    expect(onRestoreSuccess).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('Justificativa de teste válida com mais de 15 caracteres.')).toBeInTheDocument();
  });

  it('previne múltiplos envios concorrentes durante o processamento', async () => {
    const user = userEvent.setup();

    let resolvePromise!: (value: ModerateBusinessResponseResponse) => void;
    const pendingPromise = new Promise<ModerateBusinessResponseResponse>((resolve) => {
      resolvePromise = resolve;
    });

    vi.mocked(businessResponsesApi.moderateBusinessResponse).mockReturnValueOnce(pendingPromise);

    render(
      <RestoreBusinessResponseModal
        response={sampleResponse}
        isOpen={true}
        onClose={vi.fn()}
        onRestoreSuccess={vi.fn()}
      />
    );

    const justificationInput = screen.getByLabelText(/justificativa da restauração/i);
    await user.type(justificationInput, 'Justificativa para teste de concorrência com 15 caracteres.');

    await user.click(screen.getByRole('button', { name: /restaurar resposta/i }));

    const confirmButton = screen.getByRole('button', { name: /sim, restaurar/i });
    await user.click(confirmButton);

    // O botão deve estar desabilitado durante a submissão
    expect(businessResponsesApi.moderateBusinessResponse).toHaveBeenCalledTimes(1);
    expect(confirmButton).toBeDisabled();

    // Tentativa de segundo clique não deve surtir efeito
    await user.click(confirmButton);
    expect(businessResponsesApi.moderateBusinessResponse).toHaveBeenCalledTimes(1);

    // Conclui a promise
    await act(async () => {
      resolvePromise!({
        auditLogId: 'audit-log-2',
        responseId: sampleResponse.id,
        reviewId: sampleResponse.reviewId!,
        businessAccountId: sampleResponse.businessAccountId,
        placeId: sampleResponse.placeId,
        action: 'RESTORE_RESPONSE',
        reasonCode: 'REPORT_DISMISSED',
        justification: 'Justificativa para teste de concorrência com 15 caracteres.',
        previousStatus: 'REMOVED',
        newStatus: 'ACTIVE',
        moderatedAt: '2026-10-13T12:00:00Z',
      });
    });
  });
});
