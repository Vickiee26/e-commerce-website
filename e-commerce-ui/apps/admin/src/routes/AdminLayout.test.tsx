import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { setTokens } from '@shopflow/api-client'
import { API, HttpResponse, http, server } from '../test/msw'
import { renderWithProviders } from '../test/render'
import { AdminLayout } from './AdminLayout'

const PROFILE = { id: '1', email: 'admin@shopflow.test', roles: ['ADMIN'] }

beforeEach(() => {
  // The shell reads the cached session for the sidebar's email; the gate is RequireAdmin's job.
  setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
  server.use(http.get(`${API}/api/me`, () => HttpResponse.json(PROFILE)))
})

function renderLayout() {
  return renderWithProviders(<AdminLayout />, { route: '/products', path: '/products' })
}

describe('AdminLayout', () => {
  it('offers both navigation links and the signed-in address', async () => {
    renderLayout()

    expect(screen.getAllByRole('link', { name: 'Products' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: 'Categories' }).length).toBeGreaterThan(0)
    expect(await screen.findByText('admin@shopflow.test')).toBeInTheDocument()
  })

  /**
   * The drawer is only reachable below `md`, where jsdom has no layout — so these assert the
   * behaviour that CSS does not provide: that it is a modal, and that a keyboard can leave it.
   */
  it('opens the drawer as a labelled modal and returns focus to the trigger on Escape', async () => {
    renderLayout()

    const trigger = screen.getByRole('button', { name: 'Open menu' })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.click(trigger)

    const drawer = screen.getByRole('dialog')
    expect(drawer).toHaveAttribute('aria-modal', 'true')
    expect(drawer).toHaveAccessibleName('Catalogue navigation')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    await waitFor(() => expect(drawer).toHaveFocus())

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    // Closing with the keyboard has to leave the keyboard somewhere, and this is the only sensible
    // place: where the user was before they opened it.
    expect(trigger).toHaveFocus()
  })

  it('closes the drawer when a destination inside it is chosen', async () => {
    renderLayout()

    await userEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    const drawer = screen.getByRole('dialog')

    await userEvent.click(within(drawer).getByRole('link', { name: 'Categories' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('closes the drawer on its own close button', async () => {
    renderLayout()

    await userEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    await userEvent.click(screen.getByRole('button', { name: 'Close menu' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()
  })
})
