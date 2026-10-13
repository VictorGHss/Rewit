export type BusinessResponseRemovalSource = 'BUSINESS' | 'MODERATION';

export type BusinessResponseModerationAction = 'REMOVE_RESPONSE' | 'RESTORE_RESPONSE';

export interface AdminRemovedBusinessResponse {
  id: string;
  reviewId: string | null;
  discussionId: string | null;
  businessAccountId: string;
  corporateName: string;
  placeId: string;
  placeName: string;
  content: string;
  createdAt: string;
  removedBy: BusinessResponseRemovalSource;
  removedAt: string;
  restorable: boolean;
  otherActiveResponse: boolean;
  moderationReasonCode?: string | null;
  moderationJustification?: string | null;
  moderatedAt?: string | null;
}

export interface ListRemovedBusinessResponsesParams {
  page?: number;
  size?: number;
  reviewId?: string;
  discussionId?: string;
  businessAccountId?: string;
  placeId?: string;
  removedBy?: BusinessResponseRemovalSource;
}

export interface ModerateBusinessResponseRequest {
  action: BusinessResponseModerationAction;
  reasonCode: string;
  justification: string;
}

export interface ModerateBusinessResponseResponse {
  auditLogId: string;
  responseId: string;
  reviewId: string;
  discussionId?: string | null;
  businessAccountId: string;
  placeId: string;
  action: BusinessResponseModerationAction;
  reasonCode: string;
  justification: string;
  previousStatus: string;
  newStatus: string;
  removedBy?: BusinessResponseRemovalSource | null;
  moderatedAt: string;
}
