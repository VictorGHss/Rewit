export type PlaceClaimStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type PlaceClaimDecision = 'APPROVE' | 'REJECT';

export interface PlaceClaimResponse {
  id: string;
  businessAccountId: string;
  corporateName: string;
  taxId: string;
  placeId: string;
  placeName: string;
  city: string;
  state: string;
  status: PlaceClaimStatus;
  evidenceDescription: string;
  createdAt: string;
  decidedAt?: string | null;
  decisionReason?: string | null;
}

export interface PlaceClaimDecisionRequest {
  decision: PlaceClaimDecision;
  justification: string;
}

export interface ListPlaceClaimsParams {
  page?: number;
  size?: number;
  status?: PlaceClaimStatus | '';
}
