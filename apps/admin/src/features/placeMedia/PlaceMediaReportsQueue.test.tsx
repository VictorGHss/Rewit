import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PlaceMediaReportsQueue } from './PlaceMediaReportsQueue';
import { placeMediaApi } from '../../core/api/placeMedia';
import type { AdminPlaceMediaReport, PagedResponse } from '../../core/types';

vi.mock('../../core/api/placeMedia', () => ({
  placeMediaApi: {
    listReports: vi.fn(),
  },
}));

const base = {
  placeMediaId: 'media-1',
  reporterUserId: 'reporter-1',
  reason: 'SPAM',
  createdAt: '2026-01-10T10:00:00Z',
  updatedAt: '2026-01-10T10:00:00Z',
  decidedAt: '2026-01-11T10:00:00Z',
};

const page = (content: AdminPlaceMediaReport[]): PagedResponse<AdminPlaceMediaReport> => ({
  content,
  pageNumber: 0,
  pageSize: 20,
  totalElements: content.length,
  totalPages: 1,
  isLast: true,
});

describe('PlaceMediaReportsQueue (LEG-2)', () => {
  beforeEach(() => {
    vi.mocked(placeMediaApi.listReports).mockReset();
  });

  it('distingue decisão legada sem moderador, decisão humana validada e resolução automática', async () => {
    vi.mocked(placeMediaApi.listReports).mockResolvedValue(
      page([
        { ...base, id: 'legacy', status: 'ACCEPTED', legacyDecision: true, decisionOrigin: 'LEGACY', decidedByUserId: null },
        { ...base, id: 'human', status: 'REJECTED', legacyDecision: false, decisionOrigin: 'MODERATOR', decidedByUserId: 'moderator-7' },
        { ...base, id: 'system', status: 'AUTO_RESOLVED', legacyDecision: false, decisionOrigin: 'SYSTEM', decidedByUserId: null },
        { ...base, id: 'pending', status: 'PENDING', legacyDecision: false, decisionOrigin: null, decidedAt: null },
      ])
    );

    render(<PlaceMediaReportsQueue />);

    await waitFor(() => expect(screen.getByTestId('decision-origin-legacy')).toBeInTheDocument());
    expect(screen.getByTestId('decision-origin-legacy')).toHaveTextContent('Decisão histórica sem moderador verificável');
    expect(screen.getByTestId('decider-legacy')).toHaveTextContent('Moderador não identificável (registro histórico)');
    expect(screen.getByTestId('decision-origin-human')).toHaveTextContent('Decisão de moderador');
    expect(screen.getByTestId('decider-human')).toHaveTextContent('moderator-7');
    expect(screen.getByTestId('decision-origin-system')).toHaveTextContent('Resolução automática');
    expect(screen.getByTestId('decider-system')).toHaveTextContent('Sistema');
    expect(screen.getByText('Aguardando decisão')).toBeInTheDocument();
  });

  it('nunca apresenta decisor para decisão marcada como legada, mesmo se a API trouxer um valor', async () => {
    vi.mocked(placeMediaApi.listReports).mockResolvedValue(
      page([{ ...base, id: 'legacy', status: 'ACCEPTED', legacyDecision: true, decisionOrigin: 'MODERATOR', decidedByUserId: 'author-1' }])
    );

    render(<PlaceMediaReportsQueue />);

    await waitFor(() => expect(screen.getByTestId('decider-legacy')).toBeInTheDocument());
    expect(screen.getByTestId('decider-legacy')).not.toHaveTextContent('author-1');
    expect(screen.getByTestId('decision-origin-legacy')).toHaveTextContent('Decisão histórica sem moderador verificável');
  });

  it('aplica os filtros administrativos de status e de origem da decisão', async () => {
    vi.mocked(placeMediaApi.listReports).mockResolvedValue(page([]));
    render(<PlaceMediaReportsQueue />);
    await waitFor(() => expect(placeMediaApi.listReports).toHaveBeenCalledTimes(1));

    await userEvent.selectOptions(screen.getByLabelText('Filtrar por origem da decisão'), 'LEGACY');
    await waitFor(() =>
      expect(placeMediaApi.listReports).toHaveBeenLastCalledWith(
        expect.objectContaining({ decisionOrigin: 'LEGACY', status: undefined, page: 0 })
      )
    );

    await userEvent.selectOptions(screen.getByLabelText('Filtrar por status'), 'ACCEPTED');
    await waitFor(() =>
      expect(placeMediaApi.listReports).toHaveBeenLastCalledWith(
        expect.objectContaining({ decisionOrigin: 'LEGACY', status: 'ACCEPTED' })
      )
    );
    expect(await screen.findByText('Nenhuma denúncia encontrada.')).toBeInTheDocument();
  });
});
