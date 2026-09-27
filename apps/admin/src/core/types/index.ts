export interface SystemMetric {
  label: string;
  value: string | number;
  change?: string;
}

export interface ModerationItem {
  id: string;
  type: 'REVIEW' | 'PLACE_CLAIM' | 'PRODUCT_SUGGESTION';
  reportedBy: string;
  reason: string;
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED';
  createdAt: string;
}
