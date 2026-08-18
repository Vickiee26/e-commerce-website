import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { NewProductPage } from './NewProductPage'

const ABAYA_ID = '33333333-3333-3333-3333-333333333333'
const ABAYA_TYPE_ID = '44444444-4444-4444-4444-444444444444'
const KIMONO_TYPE_ID = '66666666-6666-6666-6666-666666666666'
const HIJAB_ID = '99999999-9999-9999-9999-999999999999'
const HIJAB_TYPE_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const NEW_PRODUCT_ID = '77777777-7777-7777-7777-777777777777'

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
  { id: HIJAB_ID, code: 'hijabs', name: 'Hijabs', types: [{ id: HIJAB_TYPE_ID, code: 'hijabs', name: 'Hijabs' }] },
  { id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', code: 'accessories', name: 'Accessories', types: [] },
]

function renderPage() {
  server.use(http.get(`${API}/api/categories`, () => HttpResponse.json(CATEGORIES)))
  return renderWithProviders(<NewProductPage />, {
    route: '/products/new',
    path: '/products/new',
    extraRoutes: [{ path: '/products/:id', element: <p>Product detail</p> }],
  })
}

describe('NewProductPage', () => {
  it('keeps the type select unusable until a category is chosen', async () => {
    renderPage()

    const type = await screen.findByLabelText(/^type/i)
    expect(type).toBeDisabled()
    expect(screen.getByText('Choose a category first')).toBeInTheDocument()
  })

  it('offers only the chosen category’s types, so the 404 pair cannot be built', async () => {
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText(/^category/i), ABAYA_ID)

    const type = screen.getByLabelText(/^type/i)
    expect(type).toBeEnabled()
    const values = Array.from(type.querySelectorAll('option')).map((option) => option.value)
    expect(values).toEqual(['', ABAYA_TYPE_ID, KIMONO_TYPE_ID])
    expect(values).not.toContain(HIJAB_TYPE_ID)
  })

  it('clears the chosen type when the category changes', async () => {
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText(/^category/i), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), KIMONO_TYPE_ID)
    expect(screen.getByLabelText(/^type/i)).toHaveValue(KIMONO_TYPE_ID)

    await userEvent.selectOptions(screen.getByLabelText(/^category/i), HIJAB_ID)

    expect(screen.getByLabelText(/^type/i)).toHaveValue('')
  })

  it('sends the operator to the categories screen when a category has no types', async () => {
    renderPage()

    await userEvent.selectOptions(
      await screen.findByLabelText(/^category/i),
      'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    )

    expect(screen.getByText(/“Accessories” has no types yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add a type' })).toHaveAttribute('href', '/categories')
    expect(screen.getByLabelText(/^type/i)).toBeDisabled()
  })

  it('creates the product and opens it', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/products`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json(
          { id: NEW_PRODUCT_ID, name: 'Classic Black Abaya', price: 129.5, variants: [], resources: [] },
          { status: 201 },
        )
      }),
    )
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'Classic Black Abaya')
    await userEvent.type(screen.getByLabelText(/^price/i), '129.50')
    await userEvent.selectOptions(screen.getByLabelText(/^category/i), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), ABAYA_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          name: 'Classic Black Abaya',
          price: 129.5,
          categoryId: ABAYA_ID,
          categoryTypeId: ABAYA_TYPE_ID,
        },
      ]),
    )
    expect(await screen.findByText('Product detail')).toBeInTheDocument()
  })

  it('rejects a price the numeric(12,2) column cannot hold, before any request', async () => {
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'Classic Black Abaya')
    await userEvent.type(screen.getByLabelText(/^price/i), '10.999')
    await userEvent.selectOptions(screen.getByLabelText(/^category/i), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), ABAYA_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    expect(
      await screen.findByText('Use up to ten digits and at most two decimal places'),
    ).toBeInTheDocument()
  })

  it('will not submit without a category and a type', async () => {
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'Classic Black Abaya')
    await userEvent.type(screen.getByLabelText(/^price/i), '129.50')
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    expect(await screen.findByText('Category is required')).toBeInTheDocument()
    expect(screen.getByText('Type is required')).toBeInTheDocument()
  })

  it('puts a server validation message on the field it names', async () => {
    server.use(
      http.post(`${API}/api/admin/products`, () =>
        problemResponse(400, {
          title: 'Validation failed',
          detail: 'Request validation failed',
          errors: [{ field: 'name', message: 'must not be blank' }],
        }),
      ),
    )
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'x')
    await userEvent.type(screen.getByLabelText(/^price/i), '1.00')
    await userEvent.selectOptions(screen.getByLabelText(/^category/i), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), ABAYA_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    expect(await screen.findByText('must not be blank')).toBeInTheDocument()
  })
})
