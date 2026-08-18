import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { CategoriesPage } from './CategoriesPage'

const ABAYA = {
  id: '33333333-3333-3333-3333-333333333333',
  code: 'abaya',
  name: 'Abaya',
  description: 'Outerwear',
  types: [{ id: '44444444-4444-4444-4444-444444444444', code: 'abaya', name: 'Abaya' }],
}

const TYPELESS = {
  id: '55555555-5555-5555-5555-555555555555',
  code: 'accessories',
  name: 'Accessories',
  types: [],
}

function renderPage() {
  return renderWithProviders(<CategoriesPage />, { route: '/categories', path: '/categories' })
}

describe('CategoriesPage', () => {
  it('offers a first action when the catalogue is empty', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([])))
    renderPage()

    expect(await screen.findByRole('heading', { name: 'No categories yet' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create your first category' })).toBeInTheDocument()
  })

  it('shows a Retry when the list cannot be loaded', async () => {
    server.use(http.get(`${API}/api/categories`, () => problemResponse(500, { detail: 'Internal error' })))
    renderPage()

    expect(await screen.findByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('marks a category with no types as unable to hold products', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([ABAYA, TYPELESS])))
    renderPage()

    const typeless = await screen.findByRole('listitem', { name: 'Accessories' })
    expect(within(typeless).getByText('No types — cannot hold products')).toBeInTheDocument()

    const abaya = screen.getByRole('listitem', { name: 'Abaya' })
    expect(within(abaya).queryByText('No types — cannot hold products')).not.toBeInTheDocument()
    expect(within(abaya).getByText('Abaya (abaya)')).toBeInTheDocument()
  })

  it('derives a lower-case kebab-case code from the name and creates the category', async () => {
    const posted: unknown[] = []
    let created = false
    server.use(
      http.get(`${API}/api/categories`, () => HttpResponse.json(created ? [ABAYA] : [])),
      http.post(`${API}/api/admin/categories`, async ({ request }) => {
        posted.push(await request.json())
        created = true
        return HttpResponse.json(ABAYA, { status: 201 })
      }),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')

    expect(screen.getByLabelText(/^code/i)).toHaveValue('abaya')

    await userEvent.click(screen.getByRole('button', { name: 'Create category' }))

    await waitFor(() => expect(posted).toEqual([{ name: 'Abaya', code: 'abaya' }]))
    expect(await screen.findByRole('listitem', { name: 'Abaya' })).toBeInTheDocument()
  })

  it('stops editing the code once the operator types their own', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([])))
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^code/i), 'outer-wear')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')

    expect(screen.getByLabelText(/^code/i)).toHaveValue('outer-wear')
  })

  it('rejects a code the pattern forbids before any request', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([])))
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')
    await userEvent.clear(screen.getByLabelText(/^code/i))
    await userEvent.type(screen.getByLabelText(/^code/i), 'ABAYA_1')
    await userEvent.click(screen.getByRole('button', { name: 'Create category' }))

    expect(
      await screen.findByText('Lower-case letters, digits and single hyphens only'),
    ).toBeInTheDocument()
  })

  it('shows a duplicate code on the code field rather than as a bare banner', async () => {
    server.use(
      http.get(`${API}/api/categories`, () => HttpResponse.json([])),
      http.post(`${API}/api/admin/categories`, () =>
        problemResponse(409, { title: 'Conflict', detail: 'A category with code abaya already exists' }),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')
    await userEvent.click(screen.getByRole('button', { name: 'Create category' }))

    const codeField = screen.getByLabelText(/^code/i)
    expect(await screen.findByText('A category with code abaya already exists')).toBeInTheDocument()
    expect(codeField).toHaveAttribute('aria-invalid', 'true')
    // The dialog stays open with the values intact so the operator can fix the code.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/^name/i)).toHaveValue('Abaya')
  })
})
