import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { ProductsPage } from './ProductsPage'

const CATEGORY_ID = '33333333-3333-3333-3333-333333333333'

const ABAYA = {
  id: '77777777-7777-7777-7777-777777777777',
  name: 'Classic Black Abaya',
  price: 129.5,
  categoryId: CATEGORY_ID,
  categoryName: 'Abaya',
  categoryTypeId: '44444444-4444-4444-4444-444444444444',
  categoryTypeName: 'Abaya',
  variantCount: 3,
  totalStock: 12,
  // A live summary carries archivedAt as null, so no row should wear the Archived badge.
  archivedAt: null,
}

const SOLD_OUT = {
  ...ABAYA,
  id: '88888888-8888-8888-8888-888888888888',
  name: 'Sand Abaya',
  variantCount: 2,
  totalStock: 0,
}

function page(content: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
    ...overrides,
  }
}

/** Every request the page makes, so the tests can assert on what actually went out. */
let sent: URLSearchParams[] = []

beforeEach(() => {
  sent = []
  server.use(
    http.get(`${API}/api/categories`, () =>
      HttpResponse.json([{ id: CATEGORY_ID, code: 'abaya', name: 'Abaya', types: [] }]),
    ),
    http.get(`${API}/api/admin/products`, ({ request }) => {
      sent.push(new URL(request.url).searchParams)
      return HttpResponse.json(page([ABAYA, SOLD_OUT]))
    }),
  )
})

function renderPage(search = '') {
  return renderWithProviders(<ProductsPage />, {
    route: `/products${search}`,
    path: '/products',
  })
}

describe('ProductsPage', () => {
  it('asks for live products, page 0, name ascending by default and sends no empty params', async () => {
    renderPage()

    await waitFor(() => expect(sent).toHaveLength(1))
    const query = sent[0]!
    expect(Object.fromEntries(query)).toEqual({
      archived: 'exclude',
      sort: 'name',
      direction: 'asc',
      page: '0',
      size: '20',
    })
  })

  it('shows each product with its category, price and live counts', async () => {
    renderPage()

    const row = await screen.findByRole('row', { name: /Classic Black Abaya/ })
    expect(within(row).getByText('$129.50')).toBeInTheDocument()
    expect(within(row).getByText('Abaya / Abaya')).toBeInTheDocument()
    expect(within(row).getByText('3')).toBeInTheDocument()
    expect(within(row).getByText('12')).toBeInTheDocument()

    // The headers say "live" because the backend counts unarchived variants only.
    expect(screen.getByRole('columnheader', { name: 'Live variants' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Live stock' })).toBeInTheDocument()
  })

  it('marks a product whose live stock is zero', async () => {
    renderPage()

    const row = await screen.findByRole('row', { name: /Sand Abaya/ })
    expect(within(row).getByText('Out of stock')).toBeInTheDocument()

    const stocked = screen.getByRole('row', { name: /Classic Black Abaya/ })
    expect(within(stocked).queryByText('Out of stock')).not.toBeInTheDocument()
  })

  it('links each product to its detail page', async () => {
    renderPage()

    // Both layouts are in the DOM here: jsdom loads no stylesheet, so Tailwind's `hidden md:table`
    // and `md:hidden` hide nothing and the table and the card list both render. That is the point
    // of asserting on all of them — the two presentations must agree on where a row goes.
    const links = await screen.findAllByRole('link', { name: 'Classic Black Abaya' })
    expect(links).toHaveLength(2)
    for (const link of links) {
      expect(link).toHaveAttribute('href', `/products/${ABAYA.id}`)
    }
  })

  it('debounces the search box into one request', async () => {
    renderPage()
    await waitFor(() => expect(sent).toHaveLength(1))

    await userEvent.type(screen.getByLabelText('Search products'), 'abaya')

    await waitFor(() => expect(sent.filter((query) => query.get('q') === 'abaya')).toHaveLength(1))
    // Five keystrokes must not be five requests.
    expect(sent).toHaveLength(2)
  })

  it('filters by category and returns to the first page', async () => {
    renderPage('?page=4')
    await waitFor(() => expect(sent).toHaveLength(1))

    await userEvent.selectOptions(screen.getByLabelText('Category'), CATEGORY_ID)

    await waitFor(() => expect(sent).toHaveLength(2))
    const query = sent[1]!
    expect(query.get('categoryId')).toBe(CATEGORY_ID)
    expect(query.get('page')).toBe('0')
  })

  it('switches to archived only and labels the archived rows', async () => {
    renderPage()
    await waitFor(() => expect(sent).toHaveLength(1))

    // The badge has to mean something: a live row, whose archivedAt is null, must not wear it.
    const live = await screen.findByRole('row', { name: /Classic Black Abaya/ })
    expect(within(live).queryByText('Archived')).not.toBeInTheDocument()

    server.use(
      http.get(`${API}/api/admin/products`, ({ request }) => {
        sent.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([{ ...ABAYA, archivedAt: '2026-08-01T10:00:00Z' }]))
      }),
    )
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'only')

    await waitFor(() => expect(sent[1]?.get('archived')).toBe('only'))
    const row = await screen.findByRole('row', { name: /Classic Black Abaya/ })
    expect(within(row).getByText('Archived')).toBeInTheDocument()
  })

  it('sorts by a whitelisted field and direction', async () => {
    renderPage()
    await waitFor(() => expect(sent).toHaveLength(1))

    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'price')
    await waitFor(() => expect(sent[1]?.get('sort')).toBe('price'))

    await userEvent.selectOptions(screen.getByLabelText('Direction'), 'desc')
    await waitFor(() => expect(sent[2]?.get('direction')).toBe('desc'))
    expect(sent[2]?.get('sort')).toBe('price')
  })

  it('ignores query-string values the backend would reject', async () => {
    renderPage('?sort=stock&direction=sideways&archived=deleted&page=-2')

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(Object.fromEntries(sent[0]!)).toEqual({
      archived: 'exclude',
      sort: 'name',
      direction: 'asc',
      page: '0',
      size: '20',
    })
  })

  it('pages forward and disables Previous on the first page', async () => {
    server.use(
      http.get(`${API}/api/admin/products`, ({ request }) => {
        const query = new URL(request.url).searchParams
        sent.push(query)
        return HttpResponse.json(
          page([ABAYA], {
            page: Number(query.get('page')),
            totalElements: 42,
            totalPages: 3,
            first: query.get('page') === '0',
            last: query.get('page') === '2',
          }),
        )
      }),
    )
    renderPage()

    expect(await screen.findByText('Page 1 of 3 · 42 products')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))

    await waitFor(() => expect(sent[1]?.get('page')).toBe('1'))
    expect(await screen.findByText('Page 2 of 3 · 42 products')).toBeInTheDocument()
  })

  it('explains an empty result differently when a filter is on', async () => {
    server.use(
      http.get(`${API}/api/admin/products`, ({ request }) => {
        sent.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([], { totalPages: 0 }))
      }),
    )
    renderPage('?q=nothing')

    expect(await screen.findByRole('heading', { name: 'No matching products' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
  })

  it('offers a retry when the list fails', async () => {
    server.use(
      http.get(`${API}/api/admin/products`, () => problemResponse(500, { detail: 'Internal error' })),
    )
    renderPage()

    expect(await screen.findByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
