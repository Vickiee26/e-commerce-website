export type FieldError = { field: string; message: string }

/**
 * RFC 7807 body as this backend emits it. `errors` is the backend's extension and is
 * what lets a server-side validation message land on the input that caused it.
 */
export type ProblemDetail = {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  errors?: unknown[]
}

function isFieldError(value: unknown): value is FieldError {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<FieldError>
  return typeof candidate.field === 'string' && typeof candidate.message === 'string'
}

/** Every non-2xx answer becomes one of these, so callers have a single shape to handle. */
export class ApiError extends Error {
  readonly status: number
  readonly title?: string
  readonly detail?: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, problem?: ProblemDetail) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.title = problem?.title
    this.detail = problem?.detail
    this.fieldErrors = (problem?.errors ?? []).filter(isFieldError)
  }
}

/**
 * A request that never got an HTTP answer — offline, DNS, connection refused. Status 0
 * distinguishes it from any real response so the UI can word it differently.
 */
export class NetworkError extends ApiError {
  readonly networkCause: unknown

  constructor(networkCause: unknown) {
    super(0, {
      title: 'Network error',
      detail: 'Could not reach the server. Check your connection and try again.',
    })
    this.name = 'NetworkError'
    this.networkCause = networkCause
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

/** Reads the body once. An empty or non-JSON body yields an ApiError carrying only the status. */
export async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined
  try {
    const body: unknown = await response.json()
    if (typeof body === 'object' && body !== null) {
      problem = body as ProblemDetail
    }
  } catch {
    problem = undefined
  }
  return new ApiError(response.status, problem)
}
