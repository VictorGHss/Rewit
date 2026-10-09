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

export type TargetType = 'PLACE' | 'PRODUCT' | 'SERVICE' | 'EVENT';

// ---------------------------------------------------------------------------
// Denúncias de Avaliações (Review Reports - Listagem)
// ---------------------------------------------------------------------------

export interface AdminReportResponse {
  id: string;
  reviewId: string;
  reviewAuthorUserId?: string; // Não exibir na interface
  reviewStatus?: ReviewStatus;
  reporterUserId?: string; // Não exibir na interface
  reason: ReportReason;
  detail?: string;
  status: ReportStatus;
  createdAt: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Contexto de Moderação de Avaliação (GET /admin/reviews/{reviewId}/context)
// ---------------------------------------------------------------------------

export interface ReviewTarget {
  targetId: string;
  type: TargetType | null;
  displayName: string | null;
  rating: number;
  specificComment?: string | null;
}

export interface ReviewContextData {
  id: string;
  experienceText: string;
  status: ReviewStatus;
  visibility: string;
  isAnonymous: boolean;
  isVerifiedOnSite: boolean;
  createdAt: string;
  updatedAt?: string;
  targets: ReviewTarget[];
}

export interface ReviewContextReportEntry {
  id: string;
  reason: ReportReason;
  detail?: string;
  status: ReportStatus;
  createdAt: string;
  updatedAt?: string;
}

export interface ReviewAuditLogEntry {
  action: ModerationAction;
  reasonCode: string;
  justification: string;
  previousStatus: ReviewStatus;
  newStatus: ReviewStatus;
  createdAt: string;
}

export interface AdminReviewContextResponse {
  review: ReviewContextData;
  pendingReportCount: number;
  reports: ReviewContextReportEntry[];
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
  previousStatus: ReviewStatus;
  newStatus: ReviewStatus;
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
