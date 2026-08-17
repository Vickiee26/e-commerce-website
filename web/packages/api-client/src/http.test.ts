import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setBaseUrl } from './config'
import { request } from './http'
import { ApiError, NetworkError } from './problem'
import { resetRefreshState } from './refresh'
import { AUTH_EXPIRED_EVENT, clearTokens, getAccessToken, getRefreshToken, setTokens } from './tokens'

const BASE = 'http://backend.test'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const TOKEN_PAIR = {
  accessToken: 'access-2',
  refreshToken: 'refresh-2',
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

describe('request', () => {
  it('sends the bearer token, the query string and a JSON body', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, { id: 'p1' }))
    vi.stubGlobal('fetch', fetchMock)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    const result = await request<{ id: string }>('/api/admin/products', {
      method: 'POST',
      body: { name: 'Abaya' },
      query: { archived: 'exclude', categoryId: undefined, q: '', page: 0 },
    })

    expect(result).toEqual({ id: 'p1' })
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe(`${BASE}/api/admin/products?archived=exclude&page=0`)
    expect(init!.method).toBe('POST')
    expect(init!.body).toBe('{"name":"Abaya"}')
    expect(new Headers(init!.headers).get('Authorization')).toBe('Bearer access-1')
  })

  it('omits the Authorization header when auth is false', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, TOKEN_PAIR))
    vi.stubGlobal('fetch', fetchMock)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await request('/auth/login', { method: 'POST', body: {}, auth: false })

    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init!.headers).get('Authorization')).toBeNull()
  })

  it('resolves undefined for 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })))
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await expect(request<void>('/api/admin/products/p1', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('throws an ApiError carrying fieldErrors on 400', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(400, { detail: 'invalid', errors: [{ field: 'code', message: 'must be lower-case' }] }),
      ),
    )

    await expect(request('/api/admin/categories', { method: 'POST', body: {}, auth: false })).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError &&
        error.status === 400 &&
        error.fieldErrors[0]?.field === 'code',
    )
  })

  it('turns a failed fetch into a NetworkError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))

    await expect(request('/api/categories', { auth: false })).rejects.toBeInstanceOf(NetworkError)
  })
})

describe('single-flight refresh', () => {
  it('collapses concurrent 401s into exactly one POST /auth/refresh and retries each request', async () => {
    const calls: string[] = []
    let refreshed = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        calls.push(url)
        if (url.endsWith('/auth/refresh')) {
          refreshed = true
          return jsonResponse(200, TOKEN_PAIR)
        }
        return refreshed ? jsonResponse(200, { ok: true }) : jsonResponse(401, { title: 'Unauthorized' })
      }),
    )
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    const results = await Promise.all([
      request<{ ok: boolean }>('/api/admin/products'),
      request<{ ok: boolean }>('/api/categories'),
      request<{ ok: boolean }>('/api/me'),
    ])

    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }])
    expect(calls.filter((url) => url.endsWith('/auth/refresh'))).toHaveLength(1)
    expect(getAccessToken()).toBe('access-2')
    expect(getRefreshToken()).toBe('refresh-2')
  })

  it('retries a request only once, so a second 401 surfaces', async () => {
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        calls.push(url)
        if (url.endsWith('/auth/refresh')) return jsonResponse(200, TOKEN_PAIR)
        return jsonResponse(401, { title: 'Unauthorized' })
      }),
    )
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await expect(request('/api/admin/products')).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 401,
    )
    expect(calls.filter((url) => url.endsWith('/api/admin/products'))).toHaveLength(2)
    expect(calls.filter((url) => url.endsWith('/auth/refresh'))).toHaveLength(1)
  })

  it('purges the tokens and announces auth:expired when the refresh itself fails', async () => {
    const listener = vi.fn()
    window.addEventListener(AUTH_EXPIRED_EVENT, listener)
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/auth/refresh')
          ? jsonResponse(401, { title: 'Invalid refresh token' })
          : jsonResponse(401, { title: 'Unauthorized' }),
      ),
    )
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await expect(request('/api/admin/products')).rejects.toBeInstanceOf(ApiError)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    window.removeEventListener(AUTH_EXPIRED_EVENT, listener)
  })

  it('does not attempt a refresh when no refresh token is stored', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(401, { title: 'Unauthorized' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(request('/api/admin/products')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/auth/refresh'))).toHaveLength(0)
  })
})
