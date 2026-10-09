export interface UserSummary {
  id: string;
  email: string;
  handle: string;
  displayName: string;
}

export type AuthUser = UserSummary;

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface LogoutRequest {
  refreshToken?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserSummary;
}
