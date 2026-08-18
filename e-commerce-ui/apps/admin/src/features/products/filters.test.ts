import { describe, expect, it } from 'vitest'
import { DEFAULT_FILTERS, filtersToSearchParams, parseFilters } from './filters'

describe('parseFilters', () => {
  it('falls back to the defaults for an empty query string', () => {
    expect(parseFilters(new URLSearchParams())).toEqual(DEFAULT_FILTERS)
  })

  it('reads every supported value', () => {
    expect(
      parseFilters(
        new URLSearchParams(
          'q=abaya&categoryId=c1&archived=only&sort=price&direction=desc&page=2&size=50',
        ),
      ),
    ).toEqual({
      q: 'abaya',
      categoryId: 'c1',
      archived: 'only',
      sort: 'price',
      direction: 'desc',
      page: 2,
      size: 50,
    })
  })

  it('drops values the backend would reject rather than sending a 400', () => {
    const filters = parseFilters(
      new URLSearchParams('archived=deleted&sort=stock&direction=sideways&page=-3&size=999'),
    )

    expect(filters.archived).toBe('exclude')
    expect(filters.sort).toBe('name')
    expect(filters.direction).toBe('asc')
    expect(filters.page).toBe(0)
    expect(filters.size).toBe(100)
  })

  it('treats a non-numeric page or size as absent', () => {
    const filters = parseFilters(new URLSearchParams('page=abc&size='))

    expect(filters.page).toBe(0)
    expect(filters.size).toBe(20)
  })
})

describe('filtersToSearchParams', () => {
  it('writes nothing for the default view, so /products stays clean', () => {
    expect(filtersToSearchParams(DEFAULT_FILTERS).toString()).toBe('')
  })

  it('writes only what differs from the default', () => {
    expect(
      filtersToSearchParams({ ...DEFAULT_FILTERS, q: 'abaya', archived: 'all', page: 3 }).toString(),
    ).toBe('q=abaya&archived=all&page=3')
  })
})
