export type PlaceMediaReportStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'AUTO_RESOLVED';

/**
 * Proveniência da decisão (LEG-2): MODERATOR = decisão humana comprovada na trilha de auditoria;
 * SYSTEM = resolução automática; LEGACY = decisão histórica sem moderador verificável.
 * `null` enquanto a denúncia está pendente.
 */
export type PlaceMediaDecisionOrigin = 'MODERATOR' | 'SYSTEM' | 'LEGACY';

export interface AdminPlaceMediaSummary {
  id: string;
  placeId: string;
  userId?: string | null;
  url: string;
  mimeType: string;
  sizeBytes: number;
  isPrimary: boolean;
  status: string;
  createdAt: string;
}

export interface AdminPlaceMediaReport {
  id: string;
  placeMediaId: string;
  reporterUserId: string;
  reason: string;
  detail?: string | null;
  status: PlaceMediaReportStatus;
  createdAt: string;
  updatedAt: string;
  /** Preenchido somente para decisão humana verificável; nunca para decisões legadas. */
  decidedByUserId?: string | null;
  decidedAt?: string | null;
  decisionNotes?: string | null;
  legacyDecision: boolean;
  decisionOrigin?: PlaceMediaDecisionOrigin | null;
  media?: AdminPlaceMediaSummary | null;
}
