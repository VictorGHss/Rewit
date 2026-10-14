export type BusinessResponseRemovalSource = 'BUSINESS' | 'MODERATION';

export type BusinessResponseModerationAction = 'REMOVE_RESPONSE' | 'RESTORE_RESPONSE';

/** Por que a restauração seria recusada agora (decisão B); o estado pessoal do administrador não é exposto. */
export type BusinessResponseRestorationBlocker =
  | 'REMOVED_BY_BUSINESS'
  | 'OTHER_ACTIVE_RESPONSE'
  | 'ACCOUNT_NOT_APPROVED'
  | 'ADMINISTRATOR_NOT_OPERATIONAL'
  | 'PLACE_NOT_REPRESENTED';

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
  /** Vazio quando `restorable`; ausente em versões da API anteriores à decisão B. */
  restorationBlockers?: BusinessResponseRestorationBlocker[];
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
