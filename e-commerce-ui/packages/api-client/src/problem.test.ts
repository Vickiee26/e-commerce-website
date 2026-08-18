import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError, isApiError, toApiError } from './problem'

function problemResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

describe('toApiError', () => {
  it('maps a ProblemDetail with errors[] onto fieldErrors', async () => {
    const error = await toApiError(
      problemResponse(400, {
        title: 'Validation failed',
        status: 400,
        detail: 'One or more fields are invalid',
        instance: '/api/admin/categories',
        errors: [
          { field: 'code', message: 'must be lower-case letters, digits and single hyphens' },
          { field: 'name', message: 'must not be blank' },
        ],
      }),
    )

    expect(error.status).toBe(400)
    expect(error.title).toBe('Validation failed')
    expect(error.detail).toBe('One or more fields are invalid')
    expect(error.message).toBe('One or more fields are invalid')
    expect(error.fieldErrors).toEqual([
      { field: 'code', message: 'must be lower-case letters, digits and single hyphens' },
      { field: 'name', message: 'must not be blank' },
    ])
    expect(isApiError(error)).toBe(true)
  })

  it('leaves fieldErrors empty when the body has no errors array', async () => {
    const error = await toApiError(
      problemResponse(409, { title: 'Conflict', status: 409, detail: 'Category code already exists' }),
    )

    expect(error.status).toBe(409)
    expect(error.fieldErrors).toEqual([])
    expect(error.message).toBe('Category code already exists')
  })

  it('survives a non-JSON body and falls back to a status message', async () => {
    const error = await toApiError(new Response('<html>502</html>', { status: 502 }))

    expect(error.status).toBe(502)
    expect(error.title).toBeUndefined()
    expect(error.fieldErrors).toEqual([])
    expect(error.message).toBe('Request failed with status 502')
  })

  it('discards malformed entries in errors[]', async () => {
    const error = await toApiError(
      problemResponse(400, {
        detail: 'bad',
        errors: [{ field: 'name', message: 'must not be blank' }, { field: 'price' }, 'nonsense'],
      }),
    )

    expect(error.fieldErrors).toEqual([{ field: 'name', message: 'must not be blank' }])
  })
})

describe('NetworkError', () => {
  it('reports status 0 and keeps the underlying cause', () => {
    const cause = new TypeError('Failed to fetch')
    const error = new NetworkError(cause)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(0)
    expect(error.networkCause).toBe(cause)
    expect(error.message).toContain('Could not reach the server')
  })
})
