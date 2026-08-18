import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { ImagesCard } from './ImagesCard'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const PRIMARY_ID = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'
const SECOND_ID = 'ffffffff-ffff-ffff-ffff-ffffffffffff'

const PRODUCT = {
  id: PRODUCT_ID,
  name: 'Classic Black Abaya',
  price: 129.5,
  categoryId: '33333333-3333-3333-3333-333333333333',
  categoryName: 'Abaya',
  categoryTypeId: '44444444-4444-4444-4444-444444444444',
  categoryTypeName: 'Abaya',
  variants: [],
  resources: [
    { id: PRIMARY_ID, name: 'Front', url: 'https://cdn.test/front.jpg', type: 'image', isPrimary: true },
    { id: SECOND_ID, name: 'Back', url: 'https://cdn.test/back.jpg', type: 'image', isPrimary: false },
  ],
}

beforeEach(() => {
  server.use(http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () => HttpResponse.json(PRODUCT)))
})

function renderCard(product = PRODUCT) {
  return renderWithProviders(<ImagesCard product={product} />, {
    route: `/products/${PRODUCT_ID}`,
    path: '/products/:id',
  })
}

describe('ImagesCard', () => {
  it('says what an empty gallery means for the storefront', () => {
    renderCard({ ...PRODUCT, resources: [] })

    expect(
      screen.getByText('No images yet — the product will show a placeholder to customers'),
    ).toBeInTheDocument()
  })

  it('marks the primary image and offers to promote the others', () => {
    renderCard()

    const primary = screen.getByRole('listitem', { name: 'Front' })
    expect(within(primary).getByText('Primary')).toBeInTheDocument()
    expect(within(primary).queryByRole('button', { name: 'Make Front primary' })).not.toBeInTheDocument()

    const second = screen.getByRole('listitem', { name: 'Back' })
    expect(within(second).getByRole('button', { name: 'Make Back primary' })).toBeInTheDocument()
  })

  it('previews the URL as it is typed', async () => {
    renderCard()

    await userEvent.type(screen.getByLabelText(/^image url/i), 'https://cdn.test/side.jpg')

    expect(await screen.findByRole('img', { name: 'Preview' })).toHaveAttribute(
      'src',
      'https://cdn.test/side.jpg',
    )
  })

  it('admits it when the URL does not load as an image', async () => {
    renderCard()

    await userEvent.type(screen.getByLabelText(/^image url/i), 'https://cdn.test/missing.jpg')
    fireEvent.error(await screen.findByRole('img', { name: 'Preview' }))

    expect(
      await screen.findByText('That URL did not load as an image. Check it before saving.'),
    ).toBeInTheDocument()
  })

  it('rejects a URL that is not http or https before any request', async () => {
    renderCard()

    await userEvent.type(screen.getByLabelText(/^image url/i), 'cdn.test/front.jpg')
    await userEvent.click(screen.getByRole('button', { name: 'Add image' }))

    expect(await screen.findByText('Must start with http:// or https://')).toBeInTheDocument()
  })

  it('adds an image, optionally as the primary one', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/products/${PRODUCT_ID}/resources`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({ id: 'new', url: 'https://cdn.test/side.jpg', isPrimary: true }, { status: 201 })
      }),
    )
    renderCard()

    await userEvent.type(screen.getByLabelText(/^image url/i), 'https://cdn.test/side.jpg')
    await userEvent.type(screen.getByLabelText('Label'), 'Side')
    await userEvent.click(screen.getByLabelText('Make this the primary image'))
    await userEvent.click(screen.getByRole('button', { name: 'Add image' }))

    await waitFor(() =>
      expect(posted).toEqual([
        { url: 'https://cdn.test/side.jpg', name: 'Side', type: 'image', isPrimary: true },
      ]),
    )
    expect(await screen.findByText('Image added')).toBeInTheDocument()
  })

  it('promotes an image by patching only isPrimary', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/resources/${SECOND_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ ...PRODUCT.resources[1], isPrimary: true })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Make Back primary' }))

    await waitFor(() => expect(patched).toEqual([{ isPrimary: true }]))
  })

  it('confirms before removing an image', async () => {
    let deleted = false
    server.use(
      http.delete(`${API}/api/admin/resources/${SECOND_ID}`, () => {
        deleted = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Remove Back' }))
    expect(screen.getByText(/Remove "Back"\? The image itself is not deleted from wherever it is hosted\./))
      .toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Remove image' }))

    await waitFor(() => expect(deleted).toBe(true))
  })
})
