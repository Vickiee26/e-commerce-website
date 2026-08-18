import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { VariantsCard } from './VariantsCard'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const LIVE_ID = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
const ARCHIVED_ID = 'dddddddd-dddd-dddd-dddd-dddddddddddd'

const PRODUCT = {
  id: PRODUCT_ID,
  name: 'Classic Black Abaya',
  price: 129.5,
  categoryId: '33333333-3333-3333-3333-333333333333',
  categoryName: 'Abaya',
  categoryTypeId: '44444444-4444-4444-4444-444444444444',
  categoryTypeName: 'Abaya',
  variants: [
    { id: LIVE_ID, color: 'Black', size: 'M', stockQuantity: 4 },
    { id: ARCHIVED_ID, color: 'Sand', size: 'L', stockQuantity: 0, archivedAt: '2026-08-01T10:00:00Z' },
  ],
  resources: [],
}

beforeEach(() => {
  server.use(
    http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () => HttpResponse.json(PRODUCT)),
  )
})

function renderCard(product = PRODUCT) {
  return renderWithProviders(<VariantsCard product={product} />, {
    route: `/products/${PRODUCT_ID}`,
    path: '/products/:id',
  })
}

describe('VariantsCard', () => {
  it('warns that a product with no variants cannot be bought', () => {
    renderCard({ ...PRODUCT, variants: [] })

    expect(screen.getByText('No variants yet — customers cannot buy this product')).toBeInTheDocument()
  })

  it('lists live and archived variants and labels the archived one', () => {
    renderCard()

    const live = screen.getByRole('listitem', { name: 'Black / M' })
    expect(within(live).getByText('4 in stock')).toBeInTheDocument()
    expect(within(live).queryByText('Archived')).not.toBeInTheDocument()

    const archived = screen.getByRole('listitem', { name: 'Sand / L' })
    expect(within(archived).getByText('Archived')).toBeInTheDocument()
    // Stock cannot move on an archived variant, so the control is not offered.
    expect(within(archived).queryByRole('button', { name: 'Adjust stock for Sand / L' })).not.toBeInTheDocument()
    expect(within(archived).getByRole('button', { name: 'Restore Sand / L' })).toBeInTheDocument()
  })

  it('creates a variant with an opening balance', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/products/${PRODUCT_ID}/variants`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({ id: 'new', color: 'Navy', size: 'S', stockQuantity: 7 }, { status: 201 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Add variant' }))
    await userEvent.type(screen.getByLabelText(/^colour/i), 'Navy')
    await userEvent.type(screen.getByLabelText(/^size/i), 'S')
    await userEvent.clear(screen.getByLabelText(/^opening stock/i))
    await userEvent.type(screen.getByLabelText(/^opening stock/i), '7')
    // The card's trigger and the dialog's submit share a label, so scope the click to the dialog.
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Add variant' }),
    )

    await waitFor(() => expect(posted).toEqual([{ color: 'Navy', size: 'S', stockQuantity: 7 }]))
  })

  it('edits only colour and size — there is no stock field to send', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/variants/${LIVE_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ id: LIVE_ID, color: 'Jet Black', size: 'M', stockQuantity: 4 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Edit Black / M' }))

    // Scoped to the dialog on purpose. ByLabelText also matches aria-label on any element, so an
    // unscoped /stock/i finds the card's own "Adjust stock for Black / M" button behind the dialog.
    // The claim being pinned is that the EDIT FORM has no stock control.
    expect(within(screen.getByRole('dialog')).queryByLabelText(/stock/i)).not.toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText(/^colour/i))
    await userEvent.type(screen.getByLabelText(/^colour/i), 'Jet Black')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(patched).toEqual([{ color: 'Jet Black', size: 'M' }]))
  })

  it('archives a variant and reports the change', async () => {
    let archived = false
    server.use(
      http.delete(`${API}/api/admin/variants/${LIVE_ID}`, () => {
        archived = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Archive Black / M' }))
    expect(
      screen.getByText(/Archiving "Black \/ M" removes it from sale\. Its stock and history are kept\./),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Archive' }))

    await waitFor(() => expect(archived).toBe(true))
    expect(await screen.findByText('Variant archived')).toBeInTheDocument()
  })

  it('reports the server’s before and after quantities after an adjustment', async () => {
    server.use(
      http.post(`${API}/api/admin/variants/${LIVE_ID}/stock`, () =>
        HttpResponse.json({
          variantId: LIVE_ID,
          previousQuantity: 4,
          newQuantity: 10,
          delta: 6,
          reason: 'Stock take',
        }),
      ),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock for Black / M' }))
    await userEvent.clear(screen.getByLabelText(/^new quantity/i))
    await userEvent.type(screen.getByLabelText(/^new quantity/i), '10')
    await userEvent.type(screen.getByLabelText(/^reason/i), 'Stock take')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    // The server's numbers, not the UI's guess.
    expect(await screen.findByText('Black / M stock 4 → 10')).toBeInTheDocument()
  })
})
