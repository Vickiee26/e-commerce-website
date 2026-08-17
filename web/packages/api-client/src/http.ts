import { getBaseUrl } from './config'
import { NetworkError, toApiError } from './problem'
import { ensureFresh } from './refresh'
import { getAccessToken } from './tokens'

export type QueryValue = string | number | boolean | undefined | null

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

export type RequestOptions = {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, QueryValue>
  /** false for /auth/login and /auth/refresh, which must neither carry nor renew a token. */
  auth?: boolean
  signal?: AbortSignal
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, auth = true, signal } = options
  const url = `${getBaseUrl()}${path}${buildQuery(query)}`

  let response = await send(url, method, body, auth ? getAccessToken() : null, signal)

  if (response.status === 401 && auth) {
    // Throws if the refresh fails, which is what surfaces the expired session to the caller.
    const accessToken = await ensureFresh()
    response = await send(url, method, body, accessToken, signal)
  }

  if (!response.ok) throw await toApiError(response)
  return await readBody<T>(response)
}

/** Drops undefined, null and empty values so the backend never sees `?q=` or `?categoryId=`. */
function buildQuery(query: Record<string, QueryValue> | undefined): string {
  if (query === undefined) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const search = params.toString()
  return search === '' ? '' : `?${search}`
}

async function send(
  url: string,
  method: HttpMethod,
  body: unknown,
  accessToken: string | null,
  signal: AbortSignal | undefined,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken !== null) headers.Authorization = `Bearer ${accessToken}`

  try {
    return await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (cause) {
    if (signal?.aborted === true) throw cause // an abort is not a network failure
    throw new NetworkError(cause)
  }
}

async function readBody<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T
  const text = await response.text()
  return (text === '' ? undefined : JSON.parse(text)) as T
}
