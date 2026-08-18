import { request, type LoginRequest, type TokenPair, type UserProfile } from '@shopflow/api-client'

/** auth: false — a login must not send, or renew, an existing bearer token. */
export function login(body: LoginRequest): Promise<TokenPair> {
  return request<TokenPair>('/auth/login', { method: 'POST', body, auth: false })
}

export function fetchMe(): Promise<UserProfile> {
  return request<UserProfile>('/api/me')
}

/** Revokes the refresh token server-side. Authenticated, unlike login and refresh. */
export function logout(refreshToken: string): Promise<void> {
  return request<void>('/auth/logout', { method: 'POST', body: { refreshToken } })
}
