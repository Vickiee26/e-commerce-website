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

/**
 * A hung refresh is the worst failure this module has. Every 401 waits on the one shared promise
 * above, and main.tsx awaits restoreSession() at module scope against an empty #root — so a request
 * that never settles is a blank page with no error and nothing to retry. An explicit controller
 * rather than AbortSignal.timeout so the deadline is a timer a test can advance.
 */
export const REFRESH_TIMEOUT_MS = 10_000

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

  const controller = new AbortController()
  const deadline = setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS)

  let response: Response
  try {
    response = await fetch(`${getBaseUrl()}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
      signal: controller.signal,
    })
  } catch (cause) {
    // A flaky network — or the deadline above — is not an expired session, so the tokens survive
    // and the user can retry. Deliberately the same branch: to a caller both are "ask again later".
    throw new NetworkError(cause)
  } finally {
    clearTimeout(deadline)
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
