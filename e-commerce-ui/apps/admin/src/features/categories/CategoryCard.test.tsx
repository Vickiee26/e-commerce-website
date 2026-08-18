import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { CategoryCard } from './CategoryCard'

const CATEGORY_ID = '33333333-3333-3333-3333-333333333333'
const TYPE_ID = '44444444-4444-4444-4444-444444444444'

const ABAYA = {
  id: CATEGORY_ID,
  code: 'abaya',
  name: 'Abaya',
  description: 'Outerwear',
  types: [
    { id: TYPE_ID, code: 'abaya', name: 'Abaya', description: 'Everyday' },
    { id: '66666666-6666-6666-6666-666666666666', code: 'kimono', name: 'Kimono' },
  ],
}

/** The card invalidates the list query, so every test needs the list endpoint answered. */
function renderCard(category = ABAYA) {
  server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([category])))
  return renderWithProviders(
    <ul>
      <CategoryCard category={category} />
    </ul>,
    { route: '/categories', path: '/categories' },
  )
}

describe('CategoryCard', () => {
  it('edits the name and description but never the code', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/categories/${CATEGORY_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ ...ABAYA, name: 'Abayas' })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Edit Abaya' }))

    const code = screen.getByLabelText(/^code/i)
    expect(code).toHaveValue('abaya')
    expect(code).toBeDisabled()

    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abayas')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() =>
      expect(patched).toEqual([{ name: 'Abayas', description: 'Outerwear' }]),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('names the types the cascade will take before deleting a category', async () => {
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Abaya' }))

    const dialog = screen.getByRole('dialog')
    expect(
      within(dialog).getByText(
        /Deleting "Abaya" also deletes its 2 types \(Abaya, Kimono\)\. This cannot be undone\./,
      ),
    ).toBeInTheDocument()
  })

  it('says so plainly when a category still has products', async () => {
    server.use(
      http.delete(`${API}/api/admin/categories/${CATEGORY_ID}`, () =>
        problemResponse(409, {
          title: 'Conflict',
          detail: 'Category Abaya still has 3 product(s) and cannot be deleted',
        }),
      ),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Abaya' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete category' }))

    expect(
      await screen.findByText('Category Abaya still has 3 product(s) and cannot be deleted'),
    ).toBeInTheDocument()
    // Still open, so the operator can read the reason and cancel deliberately.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('deletes a category the backend accepts', async () => {
    let deleted: string | null = null
    server.use(
      http.delete(`${API}/api/admin/categories/${CATEGORY_ID}`, () => {
        deleted = CATEGORY_ID
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Abaya' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete category' }))

    await waitFor(() => expect(deleted).toBe(CATEGORY_ID))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('adds a type under its category with a slugged code', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/categories/${CATEGORY_ID}/types`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({ id: 'new', code: 'open-abaya', name: 'Open Abaya' }, { status: 201 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Add type to Abaya' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Open Abaya')

    expect(screen.getByLabelText(/^code/i)).toHaveValue('open-abaya')

    await userEvent.click(screen.getByRole('button', { name: 'Add type' }))

    await waitFor(() => expect(posted).toEqual([{ name: 'Open Abaya', code: 'open-abaya' }]))
  })

  it('puts a duplicate type code on the code field', async () => {
    server.use(
      http.post(`${API}/api/admin/categories/${CATEGORY_ID}/types`, () =>
        problemResponse(409, { title: 'Conflict', detail: 'Type code kimono already exists in this category' }),
      ),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Add type to Abaya' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Kimono')
    await userEvent.click(screen.getByRole('button', { name: 'Add type' }))

    expect(
      await screen.findByText('Type code kimono already exists in this category'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText(/^code/i)).toHaveAttribute('aria-invalid', 'true')
  })

  it('deletes a type by its own id, not through its category', async () => {
    const calls: string[] = []
    server.use(
      http.delete(`${API}/api/admin/category-types/${TYPE_ID}`, ({ request }) => {
        calls.push(new URL(request.url).pathname)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Remove type Abaya' }))
    expect(
      screen.getByText(/Remove the type "Abaya" from "Abaya"\? This cannot be undone\./),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Remove type' }))

    await waitFor(() => expect(calls).toEqual([`/api/admin/category-types/${TYPE_ID}`]))
  })

  it('edits a type by its own id', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/category-types/${TYPE_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ id: TYPE_ID, code: 'abaya', name: 'Classic Abaya' })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Edit type Abaya' }))
    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Classic Abaya')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() =>
      expect(patched).toEqual([{ name: 'Classic Abaya', description: 'Everyday' }]),
    )
  })
})
