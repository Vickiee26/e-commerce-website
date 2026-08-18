import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { StockDialog } from './StockDialog'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const VARIANT_ID = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

const VARIANT = { id: VARIANT_ID, color: 'Black', size: 'M', stockQuantity: 4 }

function renderDialog() {
  return renderWithProviders(
    <StockDialog open onClose={() => {}} productId={PRODUCT_ID} variant={VARIANT} />,
    { route: `/products/${PRODUCT_ID}`, path: '/products/:id' },
  )
}

describe('StockDialog', () => {
  it('turns a target quantity into a signed delta', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/variants/${VARIANT_ID}/stock`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({
          variantId: VARIANT_ID,
          previousQuantity: 4,
          newQuantity: 10,
          delta: 6,
          reason: 'Stock take',
        })
      }),
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json({ id: PRODUCT_ID, variants: [], resources: [] }),
      ),
    )
    renderDialog()

    await userEvent.clear(screen.getByLabelText(/^new quantity/i))
    await userEvent.type(screen.getByLabelText(/^new quantity/i), '10')

    // The arithmetic is shown before it is sent, so nobody has to trust it blindly.
    expect(screen.getByText('Adds 6 (4 → 10)')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/^reason/i), 'Stock take')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    await waitFor(() => expect(posted).toEqual([{ delta: 6, reason: 'Stock take' }]))
    // No `deltaNonZero`: the generated schema exposes it, the request type omits it.
    expect(Object.keys(posted[0] as object)).toEqual(['delta', 'reason'])
  })

  it('sends a negative delta when the count went down', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/variants/${VARIANT_ID}/stock`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({
          variantId: VARIANT_ID,
          previousQuantity: 4,
          newQuantity: 1,
          delta: -3,
          reason: 'Damaged',
        })
      }),
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json({ id: PRODUCT_ID, variants: [], resources: [] }),
      ),
    )
    renderDialog()

    await userEvent.clear(screen.getByLabelText(/^new quantity/i))
    await userEvent.type(screen.getByLabelText(/^new quantity/i), '1')
    expect(screen.getByText('Removes 3 (4 → 1)')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/^reason/i), 'Damaged')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    await waitFor(() => expect(posted).toEqual([{ delta: -3, reason: 'Damaged' }]))
  })

  it('refuses a zero delta without touching the network', async () => {
    // No stock handler is registered; onUnhandledRequest: 'error' makes any request a failure.
    renderDialog()

    await userEvent.type(screen.getByLabelText(/^reason/i), 'No change')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    expect(
      await screen.findByText('That is the current quantity — nothing to adjust'),
    ).toBeInTheDocument()
  })

  it('requires a reason, because it is the only record of why stock moved', async () => {
    renderDialog()

    await userEvent.clear(screen.getByLabelText(/^new quantity/i))
    await userEvent.type(screen.getByLabelText(/^new quantity/i), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    expect(await screen.findByText('A reason is required')).toBeInTheDocument()
  })

  it('shows the server’s own arithmetic when the result would go negative', async () => {
    server.use(
      http.post(`${API}/api/admin/variants/${VARIANT_ID}/stock`, () =>
        problemResponse(409, {
          title: 'Insufficient stock',
          detail: 'Variant holds 4, so a change of -9 would leave -5',
        }),
      ),
    )
    renderDialog()

    await userEvent.clear(screen.getByLabelText(/^new quantity/i))
    await userEvent.type(screen.getByLabelText(/^new quantity/i), '0')
    await userEvent.type(screen.getByLabelText(/^reason/i), 'Write-off')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    expect(
      await screen.findByText('Variant holds 4, so a change of -9 would leave -5'),
    ).toBeInTheDocument()
  })
})
