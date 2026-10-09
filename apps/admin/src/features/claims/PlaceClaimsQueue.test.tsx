import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PlaceClaimsQueue } from './PlaceClaimsQueue';
import { claimsApi } from '../../core/api/claims';
import type { PlaceClaimResponse, PagedResponse } from '../../core/types';

vi.mock('../../core/api/claims', () => ({
  claimsApi: {
    listPlaceClaims: vi.fn(),
    decidePlaceClaim: vi.fn(),
  },
}));

describe('PlaceClaimsQueue', () => {
  const sampleClaims: PagedResponse<PlaceClaimResponse> = {
    content: [
      {
        id: 'claim-uuid-1',
        businessAccountId: 'account-uuid-10',
        corporateName: 'Cafeteria Central Ltda',
        taxId: '12345678000199',
        placeId: 'place-uuid-100',
        placeName: 'Café do Ponto',
        city: 'São Paulo',
        state: 'SP',
        status: 'PENDING',
        evidenceDescription: 'Somos proprietários do imóvel e do estabelecimento conforme contrato social anexo.',
        createdAt: '2026-10-09T10:00:00Z',
      },
      {
        id: 'claim-uuid-2',
        businessAccountId: 'account-uuid-20',
        corporateName: 'Livraria Moderna S.A.',
        taxId: '98765432000188',
        placeId: 'place-uuid-200',
        placeName: 'Livraria Saber',
        city: 'Curitiba',
        state: 'PR',
        status: 'APPROVED',
        evidenceDescription: 'Comprovante de IPTU e licença municipal de funcionamento de 2026.',
        createdAt: '2026-10-08T15:30:00Z',
        decidedAt: '2026-10-09T08:00:00Z',
        decisionReason: 'Documentação fiscal e localização confirmadas com a junta comercial.',
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

  it('lista solicitações de reivindicações exibindo dados da conta e local sem expor IDs de usuário', async () => {
    vi.mocked(claimsApi.listPlaceClaims).mockResolvedValueOnce(sampleClaims);

    render(<PlaceClaimsQueue />);

    expect(screen.getByText(/carregando reivindicações de locais/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Cafeteria Central Ltda')).toBeInTheDocument();
      expect(screen.getByText('12345678000199')).toBeInTheDocument();
      expect(screen.getByText('Café do Ponto')).toBeInTheDocument();
      expect(screen.getByText('São Paulo / SP')).toBeInTheDocument();
      expect(screen.getByText('Pendente')).toBeInTheDocument();

      expect(screen.getByText('Livraria Moderna S.A.')).toBeInTheDocument();
      expect(screen.getByText('98765432000188')).toBeInTheDocument();
      expect(screen.getByText('Livraria Saber')).toBeInTheDocument();
      expect(screen.getByText('Curitiba / PR')).toBeInTheDocument();
      expect(screen.getByText('Aprovada')).toBeInTheDocument();
    });

    // Garante que userId ou IDs internos de admin/moderador não aparecem
    expect(screen.queryByText(/user_id/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/decidedByUserId/i)).not.toBeInTheDocument();
  });

  it('permite filtrar por status de reivindicação recarregando os dados na página 0', async () => {
    const user = userEvent.setup();
    vi.mocked(claimsApi.listPlaceClaims).mockResolvedValue(sampleClaims);

    render(<PlaceClaimsQueue />);

    await waitFor(() => {
      expect(screen.getByText('Cafeteria Central Ltda')).toBeInTheDocument();
    });

    const statusSelect = screen.getByLabelText(/status:/i);
    await user.selectOptions(statusSelect, 'APPROVED');

    await waitFor(() => {
      expect(claimsApi.listPlaceClaims).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          status: 'APPROVED',
        })
      );
    });
  });

  it('exibe mensagem apropriada quando a fila está vazia', async () => {
    vi.mocked(claimsApi.listPlaceClaims).mockResolvedValueOnce({
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    });

    render(<PlaceClaimsQueue />);

    await waitFor(() => {
      expect(screen.getByText(/nenhuma solicitação de reivindicação de local encontrada/i)).toBeInTheDocument();
    });
  });

  it('abre modal de decisão e análise ao clicar no botão de ação', async () => {
    const user = userEvent.setup();
    vi.mocked(claimsApi.listPlaceClaims).mockResolvedValueOnce(sampleClaims);

    render(<PlaceClaimsQueue />);

    await waitFor(() => {
      expect(screen.getByText('Cafeteria Central Ltda')).toBeInTheDocument();
    });

    const actionButton = screen.getAllByRole('button', { name: /analisar/i })[0];
    await user.click(actionButton);

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(screen.getByText(/tomar decisão administrativa/i)).toBeInTheDocument();
      expect(screen.getByText(/somos proprietários do imóvel e do estabelecimento/i)).toBeInTheDocument();
    });
  });
});
