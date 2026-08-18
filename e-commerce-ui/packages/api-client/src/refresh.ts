import { getBaseUrl } from './config'
import { ApiError, NetworkError, toApiError } from './problem'
import type { TokenPair } from './schemas'
import { emitAuthExpired, getAccessToken, getRefreshToken, setTokens } from './tokens'

/**
 * The one in-flight refresh, shared by every caller. The backend rotates refresh tokens and
 * detects reuse, so a second concurrent call would present an already-spent token and could
 * invalidate the whole session.
 */
let inFlight: Promise<string> | null = null

export function ensureFresh(): Promise<string> {
  inFlight ??= runRefresh().finally(() => {
    inFlight = null
  })
  return inFlight
}

async function runRefresh(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (refreshToken === null) {
    emitAuthExpired()
    throw new ApiError(401, { title: 'Session expired', detail: 'Please sign in again.' })
  }

  let response: Response
  try {
    response = await fetch(`${getBaseUrl()}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
  } catch (cause) {
    // A flaky network is not an expired session, so the tokens survive and the user can retry.
    throw new NetworkError(cause)
  }

  if (!response.ok) {
    const error = await toApiError(response)
    emitAuthExpired()
    throw error
  }

  const pair = (await response.json()) as TokenPair
  // The store may have moved while the request was in flight: clearTokens after logout, or
  // setTokens for a fresh sign-in. Either way the new state wins; the stale success must not
  // clobber it. Do not emit expiry: the state change already handled the session.
  if (getRefreshToken() !== refreshToken) {
    throw new ApiError(401, { title: 'Session changed' })
  }
  setTokens({ accessToken: pair.accessToken, refreshToken: pair.refreshToken })
  return pair.accessToken
}

/**
 * A stored refresh token with no access token means the page was reloaded. Exchanging it once
 * on startup is what keeps an admin signed in across a refresh.
 */
export async function restoreSession(): Promise<boolean> {
  if (getAccessToken() !== null) return true
  if (getRefreshToken() === null) return false
  try {
    await ensureFresh()
    return true
  } catch {
    return false
  }
}

/** Test seam: drops any remembered in-flight promise between cases. */
export function resetRefreshState(): void {
  inFlight = null
}
