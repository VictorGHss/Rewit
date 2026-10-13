import { describe, it, expect, vi, beforeEach } from 'vitest';
import { businessResponsesApi } from './businessResponses';
import { fetchFromApi } from './client';

vi.mock('./client', () => ({
  fetchFromApi: vi.fn(),
}));

describe('businessResponsesApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('listRemovedBusinessResponses', () => {
    it('serializa parâmetros padrão de paginação quando nenhum filtro for passado', async () => {
      vi.mocked(fetchFromApi).mockResolvedValueOnce({
        content: [],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      });

      await businessResponsesApi.listRemovedBusinessResponses();

      expect(fetchFromApi).toHaveBeenCalledWith('/admin/business-responses/removed?page=0&size=20');
    });

    it('serializa todos os filtros e paginação corretamente na URL de consulta', async () => {
      vi.mocked(fetchFromApi).mockResolvedValueOnce({
        content: [],
        pageNumber: 2,
        pageSize: 15,
        totalElements: 35,
        totalPages: 3,
        isLast: false,
      });

      await businessResponsesApi.listRemovedBusinessResponses({
        page: 2,
        size: 15,
        reviewId: '11111111-1111-1111-1111-111111111111',
        discussionId: '22222222-2222-2222-2222-222222222222',
        businessAccountId: '33333333-3333-3333-3333-333333333333',
        placeId: '44444444-4444-4444-4444-444444444444',
        removedBy: 'MODERATION',
      });

      expect(fetchFromApi).toHaveBeenCalledTimes(1);
      const calledUrl = vi.mocked(fetchFromApi).mock.calls[0][0];

      expect(calledUrl).toContain('/admin/business-responses/removed?');
      expect(calledUrl).toContain('page=2');
      expect(calledUrl).toContain('size=15');
      expect(calledUrl).toContain('reviewId=11111111-1111-1111-1111-111111111111');
      expect(calledUrl).toContain('discussionId=22222222-2222-2222-2222-222222222222');
      expect(calledUrl).toContain('businessAccountId=33333333-3333-3333-3333-333333333333');
      expect(calledUrl).toContain('placeId=44444444-4444-4444-4444-444444444444');
      expect(calledUrl).toContain('removedBy=MODERATION');
    });

    it('ignora parâmetros vazios ou com espaços em branco ao montar a query', async () => {
      vi.mocked(fetchFromApi).mockResolvedValueOnce({
        content: [],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      });

      await businessResponsesApi.listRemovedBusinessResponses({
        reviewId: '   ',
        discussionId: '',
        businessAccountId: '   ',
        placeId: '',
      });

      expect(fetchFromApi).toHaveBeenCalledWith('/admin/business-responses/removed?page=0&size=20');
    });
  });

  describe('moderateBusinessResponse', () => {
    it('envia payload correto com método POST para o endpoint de moderação', async () => {
      const responseId = 'response-uuid-123';
      const mockResult = {
        auditLogId: 'audit-1',
        responseId,
        reviewId: 'review-1',
        businessAccountId: 'biz-1',
        placeId: 'place-1',
        action: 'RESTORE_RESPONSE' as const,
        reasonCode: 'REPORT_DISMISSED',
        justification: 'A resposta preenche os requisitos e foi restaurada.',
        previousStatus: 'REMOVED',
        newStatus: 'ACTIVE',
        moderatedAt: '2026-10-13T12:00:00Z',
      };

      vi.mocked(fetchFromApi).mockResolvedValueOnce(mockResult);

      const payload = {
        action: 'RESTORE_RESPONSE' as const,
        reasonCode: 'REPORT_DISMISSED',
        justification: 'A resposta preenche os requisitos e foi restaurada.',
      };

      const result = await businessResponsesApi.moderateBusinessResponse(responseId, payload);

      expect(fetchFromApi).toHaveBeenCalledWith(
        `/admin/business-responses/${responseId}/moderate`,
        {
          method: 'POST',
          body: JSON.stringify(payload),
        }
      );
      expect(result).toEqual(mockResult);
    });
  });
});
