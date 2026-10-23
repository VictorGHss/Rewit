import { fetchFromApi } from './client';
import type {
  AdminPlaceMediaReport,
  PagedResponse,
  PlaceMediaDecisionOrigin,
  PlaceMediaReportStatus,
} from '../types';

export interface ListPlaceMediaReportsParams {
  page?: number;
  size?: number;
  status?: PlaceMediaReportStatus;
  decisionOrigin?: PlaceMediaDecisionOrigin;
}

export const placeMediaApi = {
  async listReports(params: ListPlaceMediaReportsParams = {}): Promise<PagedResponse<AdminPlaceMediaReport>> {
    const query = new URLSearchParams();
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 20));
    if (params.status) query.set('status', params.status);
    if (params.decisionOrigin) query.set('decisionOrigin', params.decisionOrigin);
    return fetchFromApi<PagedResponse<AdminPlaceMediaReport>>(`/admin/place-media/reports?${query.toString()}`);
  },
};
