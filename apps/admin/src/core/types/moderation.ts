export type ReportStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export type ReportReason =
  | 'SPAM'
  | 'HARASSMENT'
  | 'HATE_SPEECH'
  | 'MISINFORMATION'
  | 'INAPPROPRIATE_CONTENT'
  | 'FRAUD';

export type ModerationAction = 'REMOVE_REVIEW' | 'RESTORE_REVIEW';

export type DiscussionModerationAction = 'REMOVE_DISCUSSION' | 'RESTORE_DISCUSSION';

export type DiscussionStatus = 'ACTIVE' | 'UNDER_REVIEW' | 'REMOVED';

export type ReviewStatus = 'ACTIVE' | 'UNDER_REVIEW' | 'REMOVED';

// ---------------------------------------------------------------------------
// Denúncias de Avaliações (Review Reports)
// ---------------------------------------------------------------------------

export interface AdminReportResponse {
  id: string;
  reviewId: string;
  reviewAuthorUserId?: string; // Não exibir na interface
  reviewStatus?: string;
  reporterUserId?: string; // Não exibir na interface
  reason: ReportReason;
  detail?: string;
  status: ReportStatus;
  createdAt: string;
  updatedAt?: string;
}

export interface ReviewTarget {
  id?: string;
  targetId: string;
  rating: number;
  specificComment?: string | null;
  comment?: string | null;
  createdAt?: string;
}

export interface ReviewContextData {
  id: string;
  experienceText: string;
  status: string;
  visibility: string;
  isAnonymous: boolean;
  isVerifiedOnSite: boolean;
  createdAt: string;
  updatedAt?: string;
  contextPlaceId?: string | null;
  targets: ReviewTarget[];
}

export interface ReviewAuditLogEntry {
  id: string;
  moderatorUserId?: string;
  action: ModerationAction;
  reasonCode: string;
  justification: string;
  previousStatus?: string;
  newStatus?: string;
  resolvedReportsCount?: number;
  reportsAffectedCount?: number;
  createdAt?: string;
  moderatedAt?: string;
}

export interface AdminReviewContextResponse {
  review: ReviewContextData;
  pendingReportCount: number;
  reports: AdminReportResponse[];
  auditHistory: ReviewAuditLogEntry[];
}

export interface ModerateReviewRequest {
  action: ModerationAction;
  reasonCode: string;
  justification: string;
}

export interface ModerateReviewResponse {
  auditLogId: string;
  reviewId: string;
  action: ModerationAction;
  reasonCode: string;
  justification: string;
  previousStatus: string;
  newStatus: string;
  resolvedReportsCount: number;
  moderatedAt: string;
}

// ---------------------------------------------------------------------------
// Denúncias de Discussões (Discussion Reports)
// ---------------------------------------------------------------------------

export interface AdminDiscussionReportResponse {
  id: string;
  discussionId: string;
  reviewId: string;
  parentId?: string | null;
  discussionAuthorUserId?: string; // Não exibir na interface
  discussionStatus: DiscussionStatus;
  reporterUserId?: string; // Não exibir na interface
  reason: ReportReason;
  detail?: string;
  status: ReportStatus;
  createdAt: string;
  updatedAt?: string;
}

export interface AdminDiscussionData {
  id: string;
  reviewId: string;
  parentId?: string | null;
  content: string;
  status: DiscussionStatus;
  isFromOwner: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface DiscussionReportEntry {
  id: string;
  reason: ReportReason;
  detail?: string;
  status: ReportStatus;
  createdAt: string;
  updatedAt?: string;
}

export interface DiscussionAuditEntry {
  id: string;
  action: DiscussionModerationAction;
  decision?: string;
  reasonCode: string;
  justification: string;
  previousStatus: DiscussionStatus;
  newStatus: DiscussionStatus;
  reportsAffectedCount: number;
  createdAt: string;
}

export interface AdminDiscussionContextResponse {
  discussion: AdminDiscussionData;
  parent?: AdminDiscussionData | null;
  pendingReportCount: number;
  reports: DiscussionReportEntry[];
  auditHistory: DiscussionAuditEntry[];
}

export interface ModerateDiscussionRequest {
  action: DiscussionModerationAction;
  reasonCode: string;
  justification: string;
}

export interface ModerateDiscussionResponse {
  auditLogId: string;
  discussionId: string;
  action: DiscussionModerationAction;
  decision?: string;
  reasonCode: string;
  justification: string;
  previousStatus: DiscussionStatus;
  newStatus: DiscussionStatus;
  resolvedReportsCount: number;
  moderatedAt: string;
}
