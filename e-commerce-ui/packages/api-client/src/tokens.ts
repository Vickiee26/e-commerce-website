const REFRESH_TOKEN_KEY = 'shopflow.refreshToken'

/** Dispatched on `window` when the session cannot be recovered. The app redirects to /login. */
export const AUTH_EXPIRED_EVENT = 'auth:expired'

/**
 * Module-level, never persisted. The API returns tokens in the response body so httpOnly
 * cookies are unavailable; keeping the access token out of storage limits the blast radius,
 * and keeping the refresh token in sessionStorage ends the session when the browser closes.
 */
let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  try {
    return sessionStorage.getItem(REFRESH_TOKEN_KEY)
  } catch {
    return null // private browsing can throw on access
  }
}

export function setTokens(tokens: { accessToken: string; refreshToken: string }): void {
  accessToken = tokens.accessToken
  try {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
  } catch {
    // Storage unavailable: the session simply will not survive a reload.
  }
}

export function clearTokens(): void {
  accessToken = null
  try {
    sessionStorage.removeItem(REFRESH_TOKEN_KEY)
  } catch {
    // Nothing to do.
  }
}

export function emitAuthExpired(): void {
  clearTokens()
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
  }
}
