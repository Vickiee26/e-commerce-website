import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'

const ARCHIVED_VALUES = ['exclude', 'only', 'all'] as const
const SORT_VALUES = ['name', 'price', 'createdAt'] as const
const DIRECTION_VALUES = ['asc', 'desc'] as const

export type ArchivedFilter = (typeof ARCHIVED_VALUES)[number]
export type ProductSort = (typeof SORT_VALUES)[number]
export type SortDirection = (typeof DIRECTION_VALUES)[number]

export const DEFAULT_PAGE_SIZE = 20
const MAX_PAGE_SIZE = 100

export type ProductFilters = {
  q: string
  categoryId: string
  archived: ArchivedFilter
  sort: ProductSort
  direction: SortDirection
  page: number
  size: number
}

export const DEFAULT_FILTERS: ProductFilters = {
  q: '',
  categoryId: '',
  archived: 'exclude',
  sort: 'name',
  direction: 'asc',
  page: 0,
  size: DEFAULT_PAGE_SIZE,
}

/** A hand-edited URL must never reach the backend as a 400, so unknown values become defaults. */
function oneOf<T extends string>(values: readonly T[], raw: string | null, fallback: T): T {
  return raw !== null && (values as readonly string[]).includes(raw) ? (raw as T) : fallback
}

function clampedInt(raw: string | null, fallback: number, min: number, max: number): number {
  if (raw === null || raw.trim() === '') return fallback
  const parsed = Number.parseInt(raw, 10)
  if (Number.isNaN(parsed)) return fallback
  return Math.min(Math.max(parsed, min), max)
}

export function parseFilters(params: URLSearchParams): ProductFilters {
  return {
    q: params.get('q') ?? '',
    categoryId: params.get('categoryId') ?? '',
    archived: oneOf(ARCHIVED_VALUES, params.get('archived'), 'exclude'),
    sort: oneOf(SORT_VALUES, params.get('sort'), 'name'),
    direction: oneOf(DIRECTION_VALUES, params.get('direction'), 'asc'),
    page: clampedInt(params.get('page'), 0, 0, Number.MAX_SAFE_INTEGER),
    size: clampedInt(params.get('size'), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE),
  }
}

/** Only non-defaults are written, so the plain list URL is just `/products`. */
export function filtersToSearchParams(filters: ProductFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.q !== '') params.set('q', filters.q)
  if (filters.categoryId !== '') params.set('categoryId', filters.categoryId)
  if (filters.archived !== DEFAULT_FILTERS.archived) params.set('archived', filters.archived)
  if (filters.sort !== DEFAULT_FILTERS.sort) params.set('sort', filters.sort)
  if (filters.direction !== DEFAULT_FILTERS.direction) params.set('direction', filters.direction)
  if (filters.page !== 0) params.set('page', String(filters.page))
  if (filters.size !== DEFAULT_PAGE_SIZE) params.set('size', String(filters.size))
  return params
}

/**
 * The query string is the state. Changing any filter other than the page resets to page 0 —
 * otherwise a narrower filter can leave you stranded on a page that no longer exists.
 */
export function useProductFilters(): {
  filters: ProductFilters
  setFilters: (patch: Partial<ProductFilters>) => void
} {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = useMemo(() => parseFilters(searchParams), [searchParams])

  const setFilters = useCallback(
    (patch: Partial<ProductFilters>): void => {
      const next: ProductFilters = { ...filters, ...patch }
      if (patch.page === undefined) next.page = 0
      // replace: typing in the search box must not bury the previous screen under history entries.
      setSearchParams(filtersToSearchParams(next), { replace: true })
    },
    [filters, setSearchParams],
  )

  return { filters, setFilters }
}
