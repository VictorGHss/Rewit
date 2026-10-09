import { fetchFromApi } from './client';
import type {
  PagedResponse,
  PlaceClaimResponse,
  PlaceClaimDecisionRequest,
  ListPlaceClaimsParams,
} from '../types';

export const claimsApi = {
  async listPlaceClaims(params: ListPlaceClaimsParams = {}): Promise<PagedResponse<PlaceClaimResponse>> {
    const query = new URLSearchParams();
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 20));

    if (params.status) {
      query.set('status', params.status);
    }

    return fetchFromApi<PagedResponse<PlaceClaimResponse>>(`/admin/place-claims?${query.toString()}`);
  },

  async decidePlaceClaim(
    claimId: string,
    request: PlaceClaimDecisionRequest
  ): Promise<PlaceClaimResponse> {
    return fetchFromApi<PlaceClaimResponse>(`/admin/place-claims/${claimId}/decision`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });
  },
};
