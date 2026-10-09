import { fetchFromApi } from './client';
import type {
  PagedResponse,
  AdminReportResponse,
  AdminReviewContextResponse,
  ModerateReviewRequest,
  ModerateReviewResponse,
  AdminDiscussionReportResponse,
  AdminDiscussionContextResponse,
  ModerateDiscussionRequest,
  ModerateDiscussionResponse,
  ReportStatus,
  ReportReason,
} from '../types';

export interface ListReviewReportsParams {
  page?: number;
  size?: number;
  status?: ReportStatus;
  reason?: ReportReason;
  reviewId?: string;
  sort?: 'asc' | 'desc';
}

export interface ListDiscussionReportsParams {
  page?: number;
  size?: number;
  status?: ReportStatus;
  reason?: ReportReason;
  discussionId?: string;
  sort?: 'asc' | 'desc';
}

export const moderationApi = {
  // -------------------------------------------------------------------------
  // Moderação de Avaliações
  // -------------------------------------------------------------------------

  async listReviewReports(params: ListReviewReportsParams = {}): Promise<PagedResponse<AdminReportResponse>> {
    const query = new URLSearchParams();
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 20));
    query.set('sort', params.sort ?? 'asc');

    if (params.status) query.set('status', params.status);
    if (params.reason) query.set('reason', params.reason);
    if (params.reviewId) query.set('reviewId', params.reviewId);

    return fetchFromApi<PagedResponse<AdminReportResponse>>(`/admin/reports?${query.toString()}`);
  },

  async getReviewContext(reviewId: string): Promise<AdminReviewContextResponse> {
    return fetchFromApi<AdminReviewContextResponse>(`/admin/reviews/${reviewId}/context`);
  },

  async moderateReview(
    reviewId: string,
    request: ModerateReviewRequest
  ): Promise<ModerateReviewResponse> {
    return fetchFromApi<ModerateReviewResponse>(`/admin/reviews/${reviewId}/moderate`, {
      method: 'POST',
      body: JSON.stringify(request),
    });
  },

  // -------------------------------------------------------------------------
  // Moderação de Discussões
  // -------------------------------------------------------------------------

  async listDiscussionReports(
    params: ListDiscussionReportsParams = {}
  ): Promise<PagedResponse<AdminDiscussionReportResponse>> {
    const query = new URLSearchParams();
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 20));
    query.set('sort', params.sort ?? 'asc');

    if (params.status) query.set('status', params.status);
    if (params.reason) query.set('reason', params.reason);
    if (params.discussionId) query.set('discussionId', params.discussionId);

    return fetchFromApi<PagedResponse<AdminDiscussionReportResponse>>(
      `/admin/discussion-reports?${query.toString()}`
    );
  },

  async getDiscussionContext(discussionId: string): Promise<AdminDiscussionContextResponse> {
    return fetchFromApi<AdminDiscussionContextResponse>(`/admin/discussions/${discussionId}`);
  },

  async moderateDiscussion(
    discussionId: string,
    request: ModerateDiscussionRequest
  ): Promise<ModerateDiscussionResponse> {
    return fetchFromApi<ModerateDiscussionResponse>(`/admin/discussions/${discussionId}/moderate`, {
      method: 'POST',
      body: JSON.stringify(request),
    });
  },
};
