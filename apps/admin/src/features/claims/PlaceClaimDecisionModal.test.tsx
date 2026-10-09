import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PlaceClaimDecisionModal } from './PlaceClaimDecisionModal';
import { claimsApi } from '../../core/api/claims';
import { ApiError } from '../../core/api/client';
import type { PlaceClaimResponse } from '../../core/types';

vi.mock('../../core/api/claims', () => ({
  claimsApi: {
    decidePlaceClaim: vi.fn(),
  },
}));

describe('PlaceClaimDecisionModal', () => {
  const pendingClaim: PlaceClaimResponse = {
    id: 'claim-uuid-pending',
    businessAccountId: 'account-uuid-1',
    corporateName: 'Padaria Estrela Ltda',
    taxId: '11222333000144',
    placeId: 'place-uuid-1',
    placeName: 'Panificadora Estrela',
    city: 'Belo Horizonte',
    state: 'MG',
    status: 'PENDING',
    evidenceDescription: 'Alvará sanitário número 8934/2026 e contrato de locação comercial registrado.',
    createdAt: '2026-10-09T09:00:00Z',
  };

  const decidedClaim: PlaceClaimResponse = {
    id: 'claim-uuid-approved',
    businessAccountId: 'account-uuid-2',
    corporateName: 'Restaurante Sabor Real',
    taxId: '55666777000188',
    placeId: 'place-uuid-2',
    placeName: 'Sabor Real Grill',
    city: 'Porto Alegre',
    state: 'RS',
    status: 'APPROVED',
    evidenceDescription: 'Comprovante de CNPJ e conta de luz no endereço do local.',
    createdAt: '2026-10-08T10:00:00Z',
    decidedAt: '2026-10-08T14:30:00Z',
    decisionReason: 'Documentação comprobatória verificada e validada.',
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renderiza os dados da solicitação e validação de justificativa mínima (15 caracteres)', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onDecisionComplete = vi.fn();

    render(
      <PlaceClaimDecisionModal
        claim={pendingClaim}
        isOpen={true}
        onClose={onClose}
        onDecisionComplete={onDecisionComplete}
      />
    );

    expect(screen.getByText('Padaria Estrela Ltda')).toBeInTheDocument();
    expect(screen.getByText('11222333000144')).toBeInTheDocument();
    expect(screen.getByText('Panificadora Estrela')).toBeInTheDocument();
    expect(screen.getByText('Belo Horizonte / MG')).toBeInTheDocument();
    expect(
      screen.getByText('Alvará sanitário número 8934/2026 e contrato de locação comercial registrado.')
    ).toBeInTheDocument();

    const approveButton = screen.getByRole('button', { name: /aprovar reivindicação/i });
    expect(approveButton).toBeDisabled();

    const textarea = screen.getByPlaceholderText(/explique detalhadamente o fundamento/i);
    await user.type(textarea, 'Muito curto');
    expect(approveButton).toBeDisabled();

    await user.type(textarea, ' agora tem mais de quinze caracteres válidos.');
    expect(approveButton).not.toBeDisabled();
  });

  it('executa aprovação com confirmação prévia e chamada de API correta', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onDecisionComplete = vi.fn();

    vi.mocked(claimsApi.decidePlaceClaim).mockResolvedValueOnce({
      ...pendingClaim,
      status: 'APPROVED',
      decidedAt: '2026-10-09T12:00:00Z',
      decisionReason: 'Comprovantes conferidos e aprovados.',
    });

    render(
      <PlaceClaimDecisionModal
        claim={pendingClaim}
        isOpen={true}
        onClose={onClose}
        onDecisionComplete={onDecisionComplete}
      />
    );

    const textarea = screen.getByPlaceholderText(/explique detalhadamente o fundamento/i);
    await user.type(textarea, 'Comprovantes conferidos e aprovados pela equipe de moderação.');

    const approveButton = screen.getByRole('button', { name: /aprovar reivindicação/i });
    await user.click(approveButton);

    // Confirmação aberta
    expect(screen.getByText('Aprovar Reivindicação')).toBeInTheDocument();
    expect(
      screen.getByText(/Deseja realmente aprovar a reivindicação do local "Panificadora Estrela"/)
    ).toBeInTheDocument();

    const confirmButton = screen.getByRole('button', { name: /confirmar aprovação/i });
    await user.click(confirmButton);

    await waitFor(() => {
      expect(claimsApi.decidePlaceClaim).toHaveBeenCalledWith('claim-uuid-pending', {
        decision: 'APPROVE',
        justification: 'Comprovantes conferidos e aprovados pela equipe de moderação.',
      });
      expect(onDecisionComplete).toHaveBeenCalled();
    });
  });

  it('executa rejeição com modal de confirmação destrutiva', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onDecisionComplete = vi.fn();

    vi.mocked(claimsApi.decidePlaceClaim).mockResolvedValueOnce({
      ...pendingClaim,
      status: 'REJECTED',
      decidedAt: '2026-10-09T12:00:00Z',
      decisionReason: 'Documentação insuficiente para comprovação.',
    });

    render(
      <PlaceClaimDecisionModal
        claim={pendingClaim}
        isOpen={true}
        onClose={onClose}
        onDecisionComplete={onDecisionComplete}
      />
    );

    const textarea = screen.getByPlaceholderText(/explique detalhadamente o fundamento/i);
    await user.type(textarea, 'Documentação fiscal não condiz com o endereço registrado.');

    const rejectButton = screen.getByRole('button', { name: /rejeitar reivindicação/i });
    await user.click(rejectButton);

    expect(screen.getByRole('heading', { name: /rejeitar reivindicação/i })).toBeInTheDocument();
    const confirmButton = screen.getByRole('button', { name: /confirmar rejeição/i });
    await user.click(confirmButton);

    await waitFor(() => {
      expect(claimsApi.decidePlaceClaim).toHaveBeenCalledWith('claim-uuid-pending', {
        decision: 'REJECT',
        justification: 'Documentação fiscal não condiz com o endereço registrado.',
      });
      expect(onDecisionComplete).toHaveBeenCalled();
    });
  });

  it('exibe justificativa gravada e não permite nova decisão em reivindicação já finalizada', () => {
    const onClose = vi.fn();
    const onDecisionComplete = vi.fn();

    render(
      <PlaceClaimDecisionModal
        claim={decidedClaim}
        isOpen={true}
        onClose={onClose}
        onDecisionComplete={onDecisionComplete}
      />
    );

    expect(screen.getByText('Documentação comprobatória verificada e validada.')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/explique detalhadamente o fundamento/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /aprovar reivindicação/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /rejeitar reivindicação/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Fechar' })).toBeInTheDocument();
  });

  it('exibe mensagem de erro quando a API rejeita a decisão', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onDecisionComplete = vi.fn();

    vi.mocked(claimsApi.decidePlaceClaim).mockRejectedValueOnce(
      new ApiError(409, {
        status: 409,
        title: 'Conflict',
        detail: 'A solicitação de reivindicação já foi decidida anteriormente.',
      })
    );

    render(
      <PlaceClaimDecisionModal
        claim={pendingClaim}
        isOpen={true}
        onClose={onClose}
        onDecisionComplete={onDecisionComplete}
      />
    );

    const textarea = screen.getByPlaceholderText(/explique detalhadamente o fundamento/i);
    await user.type(textarea, 'Tentativa de decisão concorrente para teste de erro.');

    await user.click(screen.getByRole('button', { name: /aprovar reivindicação/i }));
    await user.click(screen.getByRole('button', { name: /confirmar aprovação/i }));

    await waitFor(() => {
      expect(
        screen.getByText(/a solicitação de reivindicação já foi decidida anteriormente/i)
      ).toBeInTheDocument();
    });
  });
});
