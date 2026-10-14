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

  describe('restorationBlockers (decisão B)', () => {
    const blocked = (
      id: string,
      corporateName: string,
      restorationBlockers: AdminRemovedBusinessResponse['restorationBlockers']
    ): AdminRemovedBusinessResponse => ({
      ...sampleResponses.content[0],
      id,
      corporateName,
      restorable: (restorationBlockers ?? []).length === 0,
      restorationBlockers,
    });

    const renderRows = async (rows: AdminRemovedBusinessResponse[]) => {
      vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValueOnce({
        ...sampleResponses,
        content: rows,
        totalElements: rows.length,
      });
      render(<RemovedBusinessResponsesQueue />);
      await waitFor(() => {
        expect(screen.getByText(rows[0].corporateName)).toBeInTheDocument();
      });
    };

    const rowOf = (corporateName: string) => screen.getByText(corporateName).closest('tr') as HTMLElement;

    it('explica cada motivo de elegibilidade isolado e mantém a restauração bloqueada', async () => {
      await renderRows([
        blocked('b-1', 'Conta Pendente', ['ACCOUNT_NOT_APPROVED']),
        blocked('b-2', 'Administrador Inoperante', ['ADMINISTRATOR_NOT_OPERATIONAL']),
        blocked('b-3', 'Local Sem Vínculo', ['PLACE_NOT_REPRESENTED']),
      ]);

      expect(within(rowOf('Conta Pendente')).getByText('A conta comercial não está aprovada.')).toBeInTheDocument();
      expect(
        within(rowOf('Administrador Inoperante')).getByText('O administrador da conta comercial não está operacional.')
      ).toBeInTheDocument();
      expect(
        within(rowOf('Local Sem Vínculo')).getByText(
          'O local não está ativo ou não está vinculado à conta comercial.'
        )
      ).toBeInTheDocument();
      expect(screen.getAllByText('Não restaurável').length).toBe(3);
      screen.getAllByRole('button', { name: 'Restaurar' }).forEach((btn) => expect(btn).toBeDisabled());
      expect(screen.queryByRole('button', { name: /restaurar resposta/i })).not.toBeInTheDocument();
      // Estado pessoal do administrador nunca aparece
      expect(screen.queryByText(/suspens|desativad|excluíd/i)).not.toBeInTheDocument();
    });

    it('lista todos os motivos combinados na mesma linha, na ordem recebida', async () => {
      await renderRows([
        blocked('b-4', 'Vários Bloqueios', [
          'OTHER_ACTIVE_RESPONSE',
          'ADMINISTRATOR_NOT_OPERATIONAL',
          'PLACE_NOT_REPRESENTED',
        ]),
      ]);

      const row = rowOf('Vários Bloqueios');
      const reasons = [
        'A empresa já possui outra resposta ativa para este mesmo alvo.',
        'O administrador da conta comercial não está operacional.',
        'O local não está ativo ou não está vinculado à conta comercial.',
      ];
      reasons.forEach((reason) => expect(within(row).getByText(reason)).toBeInTheDocument());
      expect(within(row).getByRole('button', { name: 'Restaurar' })).toHaveAttribute('title', reasons.join(' '));
    });

    it('lista vazia com restorable libera a restauração; motivo desconhecido cai na explicação genérica', async () => {
      await renderRows([
        blocked('b-5', 'Elegível', []),
        blocked('b-6', 'Motivo Futuro', [
          'NOVO_MOTIVO' as unknown as NonNullable<AdminRemovedBusinessResponse['restorationBlockers']>[number],
        ]),
      ]);

      expect(within(rowOf('Elegível')).getByRole('button', { name: /restaurar resposta/i })).toBeEnabled();
      expect(
        within(rowOf('Motivo Futuro')).getByText('Esta resposta não atende aos critérios para restauração administrativa.')
      ).toBeInTheDocument();
    });

    it('sem o campo (API anterior) deduz os motivos de removedBy e otherActiveResponse', async () => {
      await renderRows([
        {
          ...sampleResponses.content[1],
          corporateName: 'Empresa Legada',
          otherActiveResponse: true,
          restorationBlockers: undefined,
        },
      ]);

      const row = rowOf('Empresa Legada');
      expect(
        within(row).getByText(
          'Removida pela própria empresa (somente remoções realizadas pela moderação podem ser restauradas).'
        )
      ).toBeInTheDocument();
      expect(within(row).getByText('A empresa já possui outra resposta ativa para este mesmo alvo.')).toBeInTheDocument();
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

  it('exibe data de moderação quando presente e não exibe quando for nula', async () => {
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses).mockResolvedValueOnce(sampleResponses);

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    // resp-1 e resp-3 têm moderatedAt presente
    const moderatedDates = screen.getAllByText(/moderada:/i);
    expect(moderatedDates.length).toBe(2);

    // O container de resp-2 (removida pela empresa, moderatedAt null) não deve conter texto "Moderada:"
    const betaRow = screen.getByText('Hamburgueria Beta S.A.').closest('tr');
    expect(betaRow).not.toBeNull();
    expect(within(betaRow!).queryByText(/moderada:/i)).not.toBeInTheDocument();
  });

  it('recupera para a última página válida quando a página atual deixa de existir após operação', async () => {
    const user = userEvent.setup();

    // Chamada inicial: página 0 com 2 páginas no total
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses)
      .mockResolvedValueOnce({
        content: [sampleResponses.content[0]],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      })
      // Usuário navega para a página 1: a API retorna vazia porque o item foi restaurado/removido
      .mockResolvedValueOnce({
        content: [],
        pageNumber: 1,
        pageSize: 1,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      })
      // Componente se recupera voltando para a página 0
      .mockResolvedValueOnce({
        content: [sampleResponses.content[0]],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      });

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    // Avança para a página 1
    const nextBtn = screen.getByRole('button', { name: /próxima página/i });
    await user.click(nextBtn);

    // Aguarda recuperação e re-renderização na página 0
    await waitFor(() => {
      expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenCalledTimes(3);
      expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 0 })
      );
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });
  });

  it('recupera para a página zero quando a lista fica totalmente vazia após operação sem loop infinito', async () => {
    const user = userEvent.setup();

    // Chamada inicial: página 0 com 2 páginas
    vi.mocked(businessResponsesApi.listRemovedBusinessResponses)
      .mockResolvedValueOnce({
        content: [sampleResponses.content[0]],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      })
      // Usuário navega para página 1: a API retorna lista totalmente vazia (totalElements: 0, totalPages: 0)
      .mockResolvedValueOnce({
        content: [],
        pageNumber: 1,
        pageSize: 1,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      })
      // Componente se recupera voltando para a página 0, onde a consulta também retorna vazia
      .mockResolvedValueOnce({
        content: [],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      });

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    const nextBtn = screen.getByRole('button', { name: /próxima página/i });
    await user.click(nextBtn);

    await waitFor(() => {
      expect(screen.getByText(/nenhuma resposta oficial removida encontrada/i)).toBeInTheDocument();
    });

    // Fez exatamente 3 requisições (página 0 inicial, página 1 que retornou vazia, página 0 de recuperação)
    expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenCalledTimes(3);
    expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0 })
    );
  });

  it('mantém a página atual válida sem efetuar requisições adicionais desnecessárias', async () => {
    const user = userEvent.setup();

    vi.mocked(businessResponsesApi.listRemovedBusinessResponses)
      .mockResolvedValueOnce({
        content: [sampleResponses.content[0]],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      })
      .mockResolvedValueOnce({
        content: [sampleResponses.content[1]],
        pageNumber: 1,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: true,
      });

    render(<RemovedBusinessResponsesQueue />);

    await waitFor(() => {
      expect(screen.getByText('Padaria Alfa Ltda')).toBeInTheDocument();
    });

    const nextBtn = screen.getByRole('button', { name: /próxima página/i });
    await user.click(nextBtn);

    await waitFor(() => {
      expect(screen.getByText('Hamburgueria Beta S.A.')).toBeInTheDocument();
    });

    // Exatamente 2 chamadas: página 0 e página 1, nenhuma chamada extra
    expect(businessResponsesApi.listRemovedBusinessResponses).toHaveBeenCalledTimes(2);
  });
});
