import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

/** Matches the absolute base URL the test setup gives the api-client. */
export const API = 'http://backend.test'

export const server = setupServer()

export { http, HttpResponse }

/** An RFC 7807 body shaped exactly like the backend's, for error-path tests. */
export function problemResponse(
  status: number,
  body: { title?: string; detail?: string; errors?: { field: string; message: string }[] },
) {
  return HttpResponse.json({ status, ...body }, { status })
}
