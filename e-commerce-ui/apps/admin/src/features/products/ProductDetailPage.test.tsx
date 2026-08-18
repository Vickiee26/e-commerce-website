import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { ProductDetailPage } from './ProductDetailPage'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const ABAYA_ID = '33333333-3333-3333-3333-333333333333'
const ABAYA_TYPE_ID = '44444444-4444-4444-4444-444444444444'
const KIMONO_TYPE_ID = '66666666-6666-6666-6666-666666666666'

const CATEGORIES = [
  {
    id: ABAYA_ID,
    code: 'abaya',
    name: 'Abaya',
    types: [
      { id: ABAYA_TYPE_ID, code: 'abaya', name: 'Abaya' },
      { id: KIMONO_TYPE_ID, code: 'kimono', name: 'Kimono' },
    ],
  },
]

const PRODUCT = {
  id: PRODUCT_ID,
  name: 'Classic Black Abaya',
  description: 'Crepe, full length',
  price: 129.5,
  categoryId: ABAYA_ID,
  categoryName: 'Abaya',
  categoryTypeId: ABAYA_TYPE_ID,
  categoryTypeName: 'Abaya',
  // Explicitly null, the way the backend really sends a live product. Omitting it here is what let
  // `archivedAt !== undefined` ship: every live row looked archived against the real API.
  archivedAt: null,
  variants: [],
  resources: [],
}

beforeEach(() => {
  server.use(
    http.get(`${API}/api/categories`, () => HttpResponse.json(CATEGORIES)),
    http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () => HttpResponse.json(PRODUCT)),
  )
})

function renderPage() {
  return renderWithProviders(<ProductDetailPage />, {
    route: `/products/${PRODUCT_ID}`,
    path: '/products/:id',
    extraRoutes: [{ path: '/products', element: <p>Product list</p> }],
  })
}

describe('ProductDetailPage', () => {
  it('loads the product into an editable Details card', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Classic Black Abaya' })).toBeInTheDocument()
    // The heading arrives with the product; the fields need the Details card's own categories
    // query, one request later. So the first field is awaited, not just got.
    expect(await screen.findByLabelText(/^name/i)).toHaveValue('Classic Black Abaya')
    expect(screen.getByLabelText(/^price/i)).toHaveValue('129.50')
    expect(screen.getByLabelText(/^category/i)).toHaveValue(ABAYA_ID)
    expect(screen.getByLabelText(/^type/i)).toHaveValue(ABAYA_TYPE_ID)
    expect(screen.getByLabelText(/^description/i)).toHaveValue('Crepe, full length')
  })

  it('patches only the fields that changed', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/products/${PRODUCT_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ ...PRODUCT, name: 'Classic Abaya' })
      }),
    )
    renderPage()

    const name = await screen.findByLabelText(/^name/i)
    await userEvent.clear(name)
    await userEvent.type(name, 'Classic Abaya')
    await userEvent.click(screen.getByRole('button', { name: 'Save details' }))

    await waitFor(() => expect(patched).toEqual([{ name: 'Classic Abaya' }]))
    expect(await screen.findByText('Product updated')).toBeInTheDocument()
  })

  it('sends the category and type together when either changes', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/products/${PRODUCT_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json(PRODUCT)
      }),
    )
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText(/^type/i), KIMONO_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Save details' }))

    await waitFor(() =>
      expect(patched).toEqual([{ categoryId: ABAYA_ID, categoryTypeId: KIMONO_TYPE_ID }]),
    )
  })

  it('archives rather than deletes, and says so', async () => {
    let archived = false
    server.use(
      http.delete(`${API}/api/admin/products/${PRODUCT_ID}`, () => {
        archived = true
        return new HttpResponse(null, { status: 204 })
      }),
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json(archived ? { ...PRODUCT, archivedAt: '2026-08-17T09:00:00Z' } : PRODUCT),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Archive product' }))

    const dialog = screen.getByRole('dialog')
    expect(
      within(dialog).getByText(
        /Archiving hides "Classic Black Abaya" from customers\. Nothing is deleted and you can restore it later\./,
      ),
    ).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Archive' }))

    await waitFor(() => expect(archived).toBe(true))
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restore product' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive product' })).not.toBeInTheDocument()
  })

  it('warns that restoring does not bring separately archived variants back', async () => {
    server.use(
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json({ ...PRODUCT, archivedAt: '2026-08-17T09:00:00Z' }),
      ),
      http.post(`${API}/api/admin/products/${PRODUCT_ID}/restore`, () => HttpResponse.json(PRODUCT)),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Restore product' }))

    expect(
      screen.getByText(
        /Restoring shows "Classic Black Abaya" to customers again\. Variants archived separately stay archived\./,
      ),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Restore' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Product restored')).toBeInTheDocument()
  })

  it('explains a missing product instead of an empty form', async () => {
    server.use(
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        problemResponse(404, { title: 'Not Found', detail: 'Product not found' }),
      ),
    )
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Not found' })).toBeInTheDocument()
    expect(screen.getByText('Product not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to products' })).toHaveAttribute('href', '/products')
    // No retry for a 404 and no form to mislead anyone.
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/^name/i)).not.toBeInTheDocument()
  })
})
