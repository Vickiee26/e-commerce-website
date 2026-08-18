import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setBaseUrl } from './config'
import { resetRefreshState, restoreSession } from './refresh'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from './tokens'

const BASE = 'http://backend.test'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const TOKEN_PAIR = {
  accessToken: 'access-new',
  refreshToken: 'refresh-new',
  tokenType: 'Bearer',
  expiresIn: 900,
}

beforeEach(() => {
  setBaseUrl(BASE)
  clearTokens()
  sessionStorage.clear()
  resetRefreshState()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('restoreSession', () => {
  it('returns true immediately when an access token is already present', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, TOKEN_PAIR))
    vi.stubGlobal('fetch', fetchMock)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    const result = await restoreSession()

    expect(result).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns false when no refresh token is stored', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, TOKEN_PAIR))
    vi.stubGlobal('fetch', fetchMock)

    const result = await restoreSession()

    expect(result).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('exchanges the stored refresh token and returns true on success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, TOKEN_PAIR)))
    // Simulate a page reload: refresh token in storage, no access token in memory
    sessionStorage.setItem('shopflow.refreshToken', 'refresh-1')

    const result = await restoreSession()

    expect(result).toBe(true)
    expect(getAccessToken()).toBe('access-new')
    expect(getRefreshToken()).toBe('refresh-new')
  })

  it('returns false when the refresh fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { title: 'Invalid refresh token' })))
    sessionStorage.setItem('shopflow.refreshToken', 'refresh-expired')

    const result = await restoreSession()

    expect(result).toBe(false)
    expect(getAccessToken()).toBeNull()
  })
})
