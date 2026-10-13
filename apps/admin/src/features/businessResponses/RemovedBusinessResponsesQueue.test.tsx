import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RemovedBusinessResponsesQueue } from './RemovedBusinessResponsesQueue';
import { businessResponsesApi } from '../../core/api/businessResponses';
import { ApiError } from '../../core/api/client';
import type { AdminRemovedBusinessResponse, PagedResponse } from '../../core/types';

vi.mock('../../core/api/businessResponses', () => ({
  businessResponsesApi: {
    listRemovedBusinessResponses: vi.fn(),
    moderateBusinessResponse: vi.fn(),
  },
}));

describe('RemovedBusinessResponsesQueue', () => {
  const sampleResponses: PagedResponse<AdminRemovedBusinessResponse> = {
    content: [
      {
        id: 'resp-1',
        reviewId: 'review-100',
        discussionId: null,
        businessAccountId: 'account-10',
        corporateName: 'Padaria Alfa Ltda',
        placeId: 'place-10',
        placeName: 'Panificadora Alfa Matriz',
        content: 'Obrigado pelo seu feedback! Lamentamos a demora.',
        createdAt: '2026-10-09T10:00:00Z',
        removedBy: 'MODERATION',
        removedAt: '2026-10-10T12:00:00Z',
        restorable: true,
        otherActiveResponse: false,
        moderationReasonCode: 'TERMS_VIOLATION',
        moderationJustification: 'Remoção cautelar sob averiguação.',
        moderatedAt: '2026-10-10T12:00:00Z',
      },
      {
        id: 'resp-2',
        reviewId: 'review-200',
        discussionId: 'discussion-200',
        businessAccountId: 'account-20',
        corporateName: 'Hamburgueria Beta S.A.',
        placeId: 'place-20',
        placeName: 'Beta Burger Downtown',
        content: 'Entramos em contato para esclarecer o ocorrido.',
        createdAt: '2026-10-08T15:00:00Z',
        removedBy: 'BUSINESS',
        removedAt: '2026-10-09T08:00:00Z',
        restorable: false,
        otherActiveResponse: false,
        moderationReasonCode: null,
        moderationJustification: null,
        moderatedAt: null,
      },
      {
        id: 'resp-3',
        reviewId: 'review-300',
        discussionId: null,
        businessAccountId: 'account-30',
        corporateName: 'Restaurante Gama',
        placeId: 'place-30',
        placeName: 'Gama Gastronomia',
        content: 'Nova resposta que foi sobreposta.',
        createdAt: '2026-10-07T11:00:00Z',
        removedBy: 'MODERATION',
        removedAt: '2026-10-08T18:00:00Z',
        restorable: false,
        otherActiveResponse: true,
        moderationReasonCode: 'SPAM',
        moderationJustification: 'Mensagens repetidas.',
        moderatedAt: '2026-10-08T18:00:00Z',
      },
    ],
    pageNumber: 0,
    pageSize: 20,
    totalElements: 3,
    totalPages: 1,
    isLast: true,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('exibe indicador de carregamento e lista registros com empresa, local, alvos e conteúdos', async () => {
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValueOnce(sampleResponses);

    render(<RemovedBusinessResponsesQueue />);

    expect(screen.getByText(/carregando respostas oficiais removidas/i)).toBeInTheDocument();

    await waitFor(() => {
      // Empresas e locais
      expect(screen.getByText(/padaria alfa ltda/i)).toBeInTheDocument();
      expect(screen.getByText(/panificadora alfa matriz/i)).toBeInTheDocument();
      expect(screen.getByText(/hamburgueria beta s\.a\./i)).toBeInTheDocument();
      expect(screen.getByText(/restaurante gama/i)).toBeInTheDocument();

      // Conteúdos
      expect(screen.getByText(/"Obrigado pelo seu feedback! Lamentamos a demora\."/)).toBeInTheDocument();
      expect(screen.getByText(/"Entramos em contato para esclarecer o ocorrido\."/)).toBeInTheDocument();

      // Alvos: diferenciação entre avaliação e discussão
      expect(screen.getAllByText('Avaliação').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Discussão').length).toBeGreaterThanOrEqual(1);
    });

    // PRIVACIDADE OBRIGATÓRIA: IDs de usuários e documentos fiscais nunca devem aparecer
    expect(screen.queryByText(/cpf/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/cnpj/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/taxid/i)).not.toBeInTheDocument();
  });

  it('diferencia visualmente registros restauráveis dos não restauráveis e explica o motivo', async () => {
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValueOnce(sampleResponses);

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    // resp-1 é restorable: true
    expect(screen.getByRole('button', { name: /restaurar resposta/i })).toBeEnabled();
    expect(screen.getByText('Restaurável')).toBeInTheDocument();

    // resp-2 e resp-3 são restorable: false
    expect(screen.getAllByText('Não restaurável').length).toBe(2);

    // Explicação de resp-2: removida pela empresa
    expect(
      screen.getByText(/removida pela própria empresa \(somente remoções realizadas pela moderação podem ser restauradas\)/i)
    ).toBeInTheDocument();

    // Explicação de resp-3: outra resposta ativa
    expect(
      screen.getByText(/a empresa já possui outra resposta ativa para este mesmo alvo/i)
    ).toBeInTheDocument();

    // Os botões de resp-2 e resp-3 estão desabilitados
    const disabledButtons = screen.getAllByRole('button', { name: 'Restaurar' });
    expect(disabledButtons.length).toBe(2);
    disabledButtons.forEach((btn) => {
      expect(btn).toBeDisabled();
    });
  });

  it('exibe mensagem quando não há registros retornados', async () => {
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(
        screen.getByText(/nenhuma resposta oficial removida encontrada/i)
      ).toBeInTheDocument();
    });
  });

  it('trata erro de carregamento com opção de tentar novamente', async () => {
    const user = userEvent.setup();
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses)
      .mockRejectedValueOnce(new ApiError(500, { title: 'Erro Interno', detail: 'Falha no banco de dados' }))
      .mockResolvedValueOnce(sampleResponses);

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(screen.getByText(/falha no banco de dados/i)).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: /tentar novamente/i });
    await user.click(retryBtn);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });
  });

  it('aplica filtros de busca reiniciando a paginação na página 0', async () => {
    const user = userEvent.setup();
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValue(sampleResponses);

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    // Seleciona origem MODERATION
    const removedBySelect = screen.getByLabelText(/origem da remoção:/i);
    await user.selectOptions(removedBySelect, 'MODERATION');

    // Preenche IDs
    const reviewInput = screen.getByLabelText(/id da avaliação:/i);
    await user.type(reviewInput, 'review-100');

    const discussionInput = screen.getByLabelText(/id da discussão:/i);
    await user.type(discussionInput, 'discussion-100');

    const bizAccountInput = screen.getByLabelText(/id da conta da empresa:/i);
    await user.type(bizAccountInput, 'account-10');

    const placeInput = screen.getByLabelText(/id do local:/i);
    await user.type(placeInput, 'place-10');

    // Clica em Aplicar Filtros
    await user.click(screen.getByRole('button', { name: /aplicar filtros/i }));

    await waitFor(() => {
      expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        removedBy: 'MODERATION',
        reviewId: 'review-100',
        discussionId: 'discussion-100',
        businessAccountId: 'account-10',
        placeId: 'place-10',
      });
    });

    // Limpar Filtros
    await user.click(screen.getByRole('button', { name: /limpar filtros/i }));

    await waitFor(() => {
      expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        removedBy: undefined,
        reviewId: undefined,
        discussionId: undefined,
        businessAccountId: undefined,
        placeId: undefined,
      });
    });
  });

  it('executa restauração com sucesso, recarregando a lista e apresentando feedback', async () => {
    const user = userEvent.setup();
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValue(sampleResponses);
    vi.mocked(businessResponsesApi.moderateBusinessResponse).mockResolvedValueOnce({
      auditLogId: 'audit-log-1',
      responseId: 'resp-1',
      reviewId: 'review-100',
      businessAccountId: 'account-10',
      placeId: 'place-10',
      action: 'RESTORE_RESPONSE',
      reasonCode: 'REPORT_DISMISSED',
      justification: 'Decisão formal revista e fundamentada com sucesso.',
      previousStatus: 'REMOVED',
      newStatus: 'ACTIVE',
      moderatedAt: '2026-10-13T12:00:00Z',
    });

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    // Abrir modal de restauração
    await user.click(screen.getByRole('button', { name: /restaurar resposta/i }));

    const modal = screen.getByRole('dialog', { name: /restaurar resposta oficial/i });
    expect(modal).toBeInTheDocument();

    const justificationInput = within(modal).getByLabelText(/justificativa da restauração/i);
    await user.type(justificationInput, 'Decisão formal revista e fundamentada com sucesso.');

    // Submeter formulário do modal
    const modalRestoreBtn = within(modal).getByRole('button', { name: 'Restaurar Resposta' });
    await user.click(modalRestoreBtn);

    // Confirmar no ConfirmationModal
    const confirmBtn = screen.getByRole('button', { name: /sim, restaurar/i });
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(businessResponsesApi.moderateBusinessResponse).toHaveBeenCalledWith(
        'resp-1',
        {
          action: 'RESTORE_RESPONSE',
          reasonCode: 'REPORT_DISMISSED',
          justification: 'Decisão formal revista e fundamentada com sucesso.',
        }
      );
      // Feedback de sucesso na tela
      expect(screen.getByText(/resposta oficial restaurada com sucesso!/i)).toBeInTheDocument();
    });

    // A lista foi recarregada
    expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenCalledTimes(2);
  });
});
