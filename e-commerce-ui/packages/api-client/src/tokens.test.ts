import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AUTH_EXPIRED_EVENT,
  clearTokens,
  emitAuthExpired,
  getAccessToken,
  getRefreshToken,
  setTokens,
} from './tokens'

beforeEach(() => {
  clearTokens()
  sessionStorage.clear()
})

describe('token store', () => {
  it('keeps the access token in memory and the refresh token in sessionStorage', () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    expect(getAccessToken()).toBe('access-1')
    expect(getRefreshToken()).toBe('refresh-1')
    expect(sessionStorage.getItem('shopflow.refreshToken')).toBe('refresh-1')
    // The access token must never be persisted: a stored one would outlive the tab.
    expect(JSON.stringify(sessionStorage)).not.toContain('access-1')
    expect(JSON.stringify(localStorage)).not.toContain('refresh-1')
  })

  it('clears both stores', () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    clearTokens()

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(sessionStorage.getItem('shopflow.refreshToken')).toBeNull()
  })

  it('emitAuthExpired clears the tokens and dispatches the event once', () => {
    const listener = vi.fn()
    window.addEventListener(AUTH_EXPIRED_EVENT, listener)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    emitAuthExpired()

    expect(listener).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    window.removeEventListener(AUTH_EXPIRED_EVENT, listener)
  })
})
