import { fetchFromApi } from './client';
import type {
  PagedResponse,
  AdminRemovedBusinessResponse,
  ListRemovedBusinessResponsesParams,
  ModerateBusinessResponseRequest,
  ModerateBusinessResponseResponse,
} from '../types';

export const businessResponsesApi = {
  async listRemovedBusinessResponses(
    params: ListRemovedBusinessResponsesParams = {}
  ): Promise<PagedResponse<AdminRemovedBusinessResponse>> {
    const query = new URLSearchParams();
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 20));

    if (params.reviewId?.trim()) query.set('reviewId', params.reviewId.trim());
    if (params.discussionId?.trim()) query.set('discussionId', params.discussionId.trim());
    if (params.businessAccountId?.trim()) query.set('businessAccountId', params.businessAccountId.trim());
    if (params.placeId?.trim()) query.set('placeId', params.placeId.trim());
    if (params.removedBy) query.set('removedBy', params.removedBy);

    return fetchFromApi<PagedResponse<AdminRemovedBusinessResponse>>(
      `/admin/business-responses/removed?${query.toString()}`
    );
  },

  async moderateBusinessResponse(
    responseId: string,
    request: ModerateBusinessResponseRequest
  ): Promise<ModerateBusinessResponseResponse> {
    return fetchFromApi<ModerateBusinessResponseResponse>(
      `/admin/business-responses/${responseId}/moderate`,
      {
        method: 'POST',
        body: JSON.stringify(request),
      }
    );
  },
};
